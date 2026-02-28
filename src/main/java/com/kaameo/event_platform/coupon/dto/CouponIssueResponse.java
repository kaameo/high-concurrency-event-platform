package com.kaameo.event_platform.coupon.dto;

import java.util.UUID;

public record CouponIssueResponse(
        UUID requestId,
        String status,
        String message
) {
}
