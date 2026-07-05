package com.example.search.dto;

import java.util.List;

public record SearchResponse(
        String query,
        String tenantId,
        long total,
        long tookMs,
        List<SearchResultItem> results
) {}
