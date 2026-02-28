package com.kaameo.event_platform.coupon.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisIdempotencyManager {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    public boolean checkAndSet(String idempotencyKey, UUID requestId) {
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + idempotencyKey, requestId.toString(), TTL);
        return Boolean.TRUE.equals(result);
    }
}
