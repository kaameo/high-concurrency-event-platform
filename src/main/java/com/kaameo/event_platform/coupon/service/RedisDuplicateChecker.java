package com.kaameo.event_platform.coupon.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisDuplicateChecker {

    private final StringRedisTemplate redisTemplate;

    private static final Duration TTL = Duration.ofHours(24);

    public boolean checkAndMark(UUID couponEventId, Long userId) {
        String key = "coupon:" + couponEventId + ":issued:" + userId;
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", TTL);
        return Boolean.TRUE.equals(result);
    }
}
