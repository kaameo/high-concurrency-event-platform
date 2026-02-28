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
@Table(name = "settlement_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementReport {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "coupon_event_id", nullable = false, columnDefinition = "uuid")
    private UUID couponEventId;

    @Column(name = "total_issued", nullable = false)
    private long totalIssued;

    @Column(name = "total_pending", nullable = false)
    private long totalPending;

    @Column(name = "total_failed", nullable = false)
    private long totalFailed;

    @Column(name = "redis_remaining", nullable = false)
    private long redisRemaining;

    @Column(name = "db_issued_count", nullable = false)
    private long dbIssuedCount;

    @Column(name = "is_consistent", nullable = false)
    private boolean isConsistent;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public SettlementReport(UUID couponEventId, long totalIssued, long totalPending,
                            long totalFailed, long redisRemaining, long dbIssuedCount,
                            boolean isConsistent, LocalDateTime settledAt) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.couponEventId = couponEventId;
        this.totalIssued = totalIssued;
        this.totalPending = totalPending;
        this.totalFailed = totalFailed;
        this.redisRemaining = redisRemaining;
        this.dbIssuedCount = dbIssuedCount;
        this.isConsistent = isConsistent;
        this.settledAt = settledAt;
        this.createdAt = LocalDateTime.now();
    }
}
