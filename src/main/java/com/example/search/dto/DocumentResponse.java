package com.example.search.dto;

import java.time.Instant;
import java.util.Map;

public record DocumentResponse(
        String id,
        String tenantId,
        String title,
        String body,
        Map<String, Object> metadata,
        Instant createdAt
) {}
