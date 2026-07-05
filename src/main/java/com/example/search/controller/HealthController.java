package com.example.search.controller;

import com.example.search.dto.HealthResponse;
import com.example.search.service.CacheService;
import com.example.search.service.ElasticsearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final ElasticsearchService elasticsearchService;
    private final CacheService cacheService;

    public HealthController(ElasticsearchService elasticsearchService, CacheService cacheService) {
        this.elasticsearchService = elasticsearchService;
        this.cacheService = cacheService;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        boolean esUp = elasticsearchService.ping();
        boolean redisUp = cacheService.ping();
        Map<String, String> dependencies = Map.of(
                "elasticsearch", esUp ? "up" : "down",
                "redis", redisUp ? "up" : "down"
        );
        String status = esUp && redisUp ? "healthy" : "degraded";
        return new HealthResponse(status, dependencies);
    }
}
