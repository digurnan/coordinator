package com.example.search.controller;

import com.example.search.dto.DocumentCreateRequest;
import com.example.search.dto.DocumentCreatedResponse;
import com.example.search.dto.DocumentResponse;
import com.example.search.exception.NotFoundException;
import com.example.search.service.CacheService;
import com.example.search.service.ElasticsearchService;
import com.example.search.service.RateLimiterService;
import com.example.search.validation.DocumentValidator;
import com.example.search.web.TenantResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final ElasticsearchService elasticsearchService;
    private final CacheService cacheService;
    private final RateLimiterService rateLimiterService;
    private final DocumentValidator documentValidator;

    public DocumentController(
            ElasticsearchService elasticsearchService,
            CacheService cacheService,
            RateLimiterService rateLimiterService,
            DocumentValidator documentValidator
    ) {
        this.elasticsearchService = elasticsearchService;
        this.cacheService = cacheService;
        this.rateLimiterService = rateLimiterService;
        this.documentValidator = documentValidator;
    }

    @PostMapping
    public ResponseEntity<DocumentCreatedResponse> createDocument(
            @Valid @RequestBody DocumentCreateRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenant,
            @RequestParam(value = "tenant", required = false) String queryTenant
    ) throws IOException {
        String tenantId = TenantResolver.resolve(headerTenant, queryTenant);
        documentValidator.validate(request);
        rateLimiterService.checkLimit(tenantId);

        DocumentCreatedResponse created = elasticsearchService.indexDocument(
                tenantId,
                request.title(),
                request.body(),
                request.metadata()
        );
        cacheService.invalidateTenant(tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public DocumentResponse getDocument(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenant,
            @RequestParam(value = "tenant", required = false) String queryTenant
    ) throws IOException {
        String tenantId = TenantResolver.resolve(headerTenant, queryTenant);
        rateLimiterService.checkLimit(tenantId);

        DocumentResponse cached = cacheService.getDocument(tenantId, id);
        if (cached != null) {
            return cached;
        }

        DocumentResponse document = elasticsearchService.getDocument(tenantId, id);
        if (document == null) {
            throw new NotFoundException("Document not found");
        }
        cacheService.setDocument(tenantId, id, document);
        return document;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenant,
            @RequestParam(value = "tenant", required = false) String queryTenant
    ) throws IOException {
        String tenantId = TenantResolver.resolve(headerTenant, queryTenant);
        rateLimiterService.checkLimit(tenantId);

        boolean deleted = elasticsearchService.deleteDocument(tenantId, id);
        if (!deleted) {
            throw new NotFoundException("Document not found");
        }
        cacheService.invalidateDocument(tenantId, id);
        cacheService.invalidateTenant(tenantId);
        return ResponseEntity.noContent().build();
    }
}
