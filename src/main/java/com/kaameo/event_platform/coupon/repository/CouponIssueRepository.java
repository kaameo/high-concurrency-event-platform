package com.kaameo.event_platform.coupon.repository;

import com.kaameo.event_platform.coupon.domain.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, UUID> {

    Optional<CouponIssue> findByRequestId(UUID requestId);

    Optional<CouponIssue> findByIdempotencyKey(String idempotencyKey);

    boolean existsByCouponEventIdAndUserId(UUID couponEventId, Long userId);

    long countByCouponEventId(UUID couponEventId);

    void deleteByCouponEventId(UUID couponEventId);
}
