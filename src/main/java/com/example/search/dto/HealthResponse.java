package com.example.search.dto;

import java.util.Map;

public record HealthResponse(
        String status,
        Map<String, String> dependencies
) {}
