package com.example.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Elasticsearch elasticsearch,
        Cache cache,
        RateLimit rateLimit,
        Document document
) {
    public record Elasticsearch(String url, String index) {}
    public record Cache(int searchTtlSeconds, int documentTtlSeconds) {}
    public record RateLimit(int requestsPerMinute) {}
    public record Document(int maxBodyBytes) {}
}
