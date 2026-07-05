package com.example.search.dto;

import java.time.Instant;

public record DocumentCreatedResponse(
        String id,
        String tenantId,
        String title,
        Instant createdAt
) {}
