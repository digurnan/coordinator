package com.example.search.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

public record IndexedDocument(
        @JsonProperty("tenant_id") String tenantId,
        String title,
        String body,
        Map<String, Object> metadata,
        @JsonProperty("created_at") Instant createdAt
) {}
