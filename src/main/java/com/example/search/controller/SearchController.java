package com.example.search.controller;

import com.example.search.dto.SearchResponse;
import com.example.search.service.CacheService;
import com.example.search.service.ElasticsearchService;
import com.example.search.service.RateLimiterService;
import com.example.search.web.TenantResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class SearchController {

    private final ElasticsearchService elasticsearchService;
    private final CacheService cacheService;
    private final RateLimiterService rateLimiterService;

    public SearchController(
            ElasticsearchService elasticsearchService,
            CacheService cacheService,
            RateLimiterService rateLimiterService
    ) {
        this.elasticsearchService = elasticsearchService;
        this.cacheService = cacheService;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam("q") String query,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenant,
            @RequestParam(value = "tenant", required = false) String queryTenant,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) throws IOException {
        String tenantId = TenantResolver.resolve(headerTenant, queryTenant);
        rateLimiterService.checkLimit(tenantId);

        CacheService.CachedSearch cached = cacheService.getSearch(tenantId, query);
        if (cached != null) {
            return new SearchResponse(query, tenantId, cached.total(), cached.tookMs(), cached.results());
        }

        ElasticsearchService.SearchResult result = elasticsearchService.search(tenantId, query, Math.min(size, 100));
        cacheService.setSearch(tenantId, query, result.total(), result.tookMs(), result.results());

        return new SearchResponse(query, tenantId, result.total(), result.tookMs(), result.results());
    }
}
