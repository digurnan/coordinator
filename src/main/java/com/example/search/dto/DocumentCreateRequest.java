package com.example.search.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record DocumentCreateRequest(
        @NotBlank String title,
        @NotBlank String body,
        Map<String, Object> metadata
) {
    public DocumentCreateRequest {
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
