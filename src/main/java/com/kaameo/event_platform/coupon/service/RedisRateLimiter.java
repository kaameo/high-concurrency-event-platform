package com.kaameo.event_platform.coupon.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "rate_limit:";

    public boolean isAllowed(Long userId, int maxRequests, Duration window) {
        String key = KEY_PREFIX + userId;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - window.toMillis();

        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();

        zSetOps.removeRangeByScore(key, 0, windowStart);
        Long count = zSetOps.zCard(key);

        if (count != null && count >= maxRequests) {
            return false;
        }

        zSetOps.add(key, UUID.randomUUID().toString(), now);
        redisTemplate.expire(key, window.plusSeconds(1));
        return true;
    }
}
