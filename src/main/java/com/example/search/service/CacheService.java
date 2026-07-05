package com.example.search.service;

import com.example.search.config.AppProperties;
import com.example.search.dto.DocumentResponse;
import com.example.search.dto.SearchResultItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class CacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration searchTtl;
    private final Duration documentTtl;

    public CacheService(StringRedisTemplate redis, ObjectMapper objectMapper, AppProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.searchTtl = Duration.ofSeconds(properties.cache().searchTtlSeconds());
        this.documentTtl = Duration.ofSeconds(properties.cache().documentTtlSeconds());
    }

    public CachedSearch getSearch(String tenantId, String query) {
        String raw = redis.opsForValue().get(searchKey(tenantId, query));
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, CachedSearch.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public void setSearch(String tenantId, String query, long total, long tookMs, List<SearchResultItem> results) {
        try {
            CachedSearch cached = new CachedSearch(total, tookMs, results);
            redis.opsForValue().set(searchKey(tenantId, query), objectMapper.writeValueAsString(cached), searchTtl);
        } catch (JsonProcessingException ignored) {
            // skip cache write on serialization failure
        }
    }

    public DocumentResponse getDocument(String tenantId, String docId) {
        String raw = redis.opsForValue().get(documentKey(tenantId, docId));
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, DocumentResponse.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public void setDocument(String tenantId, String docId, DocumentResponse document) {
        try {
            redis.opsForValue().set(
                    documentKey(tenantId, docId),
                    objectMapper.writeValueAsString(document),
                    documentTtl
            );
        } catch (JsonProcessingException ignored) {
            // skip cache write on serialization failure
        }
    }

    public void invalidateDocument(String tenantId, String docId) {
        redis.delete(documentKey(tenantId, docId));
    }

    public void invalidateTenant(String tenantId) {
        invalidateByPattern("search:" + tenantId + ":*");
        invalidateByPattern("doc:" + tenantId + ":*");
    }

    public boolean ping() {
        try {
            String pong = redis.execute(connection -> connection.serverCommands().ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception ex) {
            return false;
        }
    }

    private void invalidateByPattern(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String searchKey(String tenantId, String query) {
        return "search:" + tenantId + ":" + hashQuery(query);
    }

    private String documentKey(String tenantId, String docId) {
        return "doc:" + tenantId + ":" + docId;
    }

    private String hashQuery(String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(query.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(query.hashCode());
        }
    }

    public record CachedSearch(long total, long tookMs, List<SearchResultItem> results) {}
}
