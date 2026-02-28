package com.kaameo.event_platform.coupon.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CouponIssueRequest(
        @NotNull UUID couponEventId,
        @NotNull Long userId
) {
}
