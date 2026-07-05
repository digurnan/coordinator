package com.example.search.dto;

import java.time.Instant;

public record SearchResultItem(
        String id,
        String title,
        String snippet,
        double score,
        Instant createdAt
) {}
