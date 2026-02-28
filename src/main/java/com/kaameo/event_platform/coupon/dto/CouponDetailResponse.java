package com.kaameo.event_platform.coupon.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CouponDetailResponse(
        UUID couponEventId,
        String name,
        Integer totalStock,
        Long remainingStock,
        String status,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}
