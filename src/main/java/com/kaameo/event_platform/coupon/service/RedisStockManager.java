package com.kaameo.event_platform.coupon.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisStockManager {

    private final StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY_PREFIX = "coupon:stock:";

    public void initStock(UUID couponEventId, int totalStock) {
        redisTemplate.opsForValue().set(stockKey(couponEventId), String.valueOf(totalStock));
    }

    public boolean decrementStock(UUID couponEventId) {
        Long remaining = redisTemplate.opsForValue().decrement(stockKey(couponEventId));
        if (remaining == null || remaining < 0) {
            redisTemplate.opsForValue().increment(stockKey(couponEventId));
            return false;
        }
        return true;
    }

    public void restoreStock(UUID couponEventId) {
        redisTemplate.opsForValue().increment(stockKey(couponEventId));
    }

    public long getRemainingStock(UUID couponEventId) {
        String value = redisTemplate.opsForValue().get(stockKey(couponEventId));
        return value != null ? Long.parseLong(value) : 0;
    }

    private String stockKey(UUID couponEventId) {
        return STOCK_KEY_PREFIX + couponEventId;
    }
}
