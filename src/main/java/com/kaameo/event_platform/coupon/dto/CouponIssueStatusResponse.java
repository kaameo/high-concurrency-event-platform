package com.kaameo.event_platform.coupon.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CouponIssueStatusResponse(
        UUID requestId,
        UUID couponEventId,
        Long userId,
        String status,
        LocalDateTime issuedAt
) {
}
