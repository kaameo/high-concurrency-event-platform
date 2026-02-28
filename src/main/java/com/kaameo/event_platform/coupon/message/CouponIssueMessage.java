package com.kaameo.event_platform.coupon.message;

import java.time.LocalDateTime;
import java.util.UUID;

public record CouponIssueMessage(
        UUID requestId,
        UUID couponEventId,
        Long userId,
        String idempotencyKey,
        LocalDateTime requestedAt
) {
}
