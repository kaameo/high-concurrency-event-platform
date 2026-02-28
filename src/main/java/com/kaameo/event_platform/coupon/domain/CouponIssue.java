package com.kaameo.event_platform.coupon.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "coupon_issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "request_id", nullable = false, unique = true, columnDefinition = "uuid")
    private UUID requestId;

    @Column(name = "coupon_event_id", nullable = false, columnDefinition = "uuid")
    private UUID couponEventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IssueStatus status;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public CouponIssue(UUID requestId, UUID couponEventId, Long userId,
                       String idempotencyKey, IssueStatus status) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.requestId = requestId;
        this.couponEventId = couponEventId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public void markIssued() {
        this.status = IssueStatus.ISSUED;
        this.issuedAt = LocalDateTime.now();
    }

    public void markRejected(IssueStatus rejectionStatus) {
        this.status = rejectionStatus;
    }
}
