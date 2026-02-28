package com.kaameo.event_platform.coupon.dto;

public record CouponIssueStats(
        long totalIssued,
        long totalPending,
        long totalFailed
) {
}
