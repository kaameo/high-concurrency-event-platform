package com.kaameo.event_platform.coupon.dto;

import com.kaameo.event_platform.coupon.domain.IssueStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record CouponIssueSearchCondition(
        UUID couponEventId,
        Long userId,
        IssueStatus status,
        LocalDateTime startDate,
        LocalDateTime endDate
) {
}
