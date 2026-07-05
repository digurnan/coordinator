package com.example.search.service;

import com.example.search.config.AppProperties;
import com.example.search.exception.RateLimitExceededException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimiterService {

    private final StringRedisTemplate redis;
    private final int limitPerMinute;

    public RateLimiterService(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.limitPerMinute = properties.rateLimit().requestsPerMinute();
    }

    public void checkLimit(String tenantId) {
        long window = System.currentTimeMillis() / 60_000;
        String key = "ratelimit:" + tenantId + ":" + window;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(60));
        }
        if (count != null && count > limitPerMinute) {
            throw new RateLimitExceededException();
        }
    }
}
