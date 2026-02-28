package com.kaameo.event_platform.coupon.controller;

import com.kaameo.event_platform.common.dto.ApiResponse;
import com.kaameo.event_platform.coupon.domain.CouponEvent;
import com.kaameo.event_platform.coupon.domain.CouponIssue;
import com.kaameo.event_platform.coupon.domain.IssueStatus;
import com.kaameo.event_platform.coupon.dto.CouponIssueSearchCondition;
import com.kaameo.event_platform.coupon.dto.CouponIssueStats;
import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.repository.CouponEventRepository;
import com.kaameo.event_platform.coupon.repository.CouponIssueQueryRepository;
import com.kaameo.event_platform.coupon.service.RedisStockManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/coupons")
@RequiredArgsConstructor
@Tag(name = "Coupon Admin", description = "쿠폰 관리 API")
public class CouponAdminController {

    private final CouponEventRepository couponEventRepository;
    private final RedisStockManager redisStockManager;
    private final CouponIssueQueryRepository couponIssueQueryRepository;

    @PostMapping("/{couponEventId}/init-stock")
    @Operation(summary = "Redis 재고 초기화", description = "쿠폰 이벤트의 Redis 재고를 초기화합니다.")
    public ResponseEntity<ApiResponse<String>> initStock(@PathVariable UUID couponEventId) {
        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new CouponNotFoundException("Coupon event not found: " + couponEventId));

        redisStockManager.initStock(couponEventId, event.getTotalStock());

        return ResponseEntity.ok(ApiResponse.ok("재고 초기화 완료: " + event.getTotalStock() + "개"));
    }

    @GetMapping("/{couponEventId}/issues")
    @Operation(summary = "발급 내역 조회", description = "쿠폰 발급 내역을 필터링하여 조회합니다.")
    public ResponseEntity<ApiResponse<Page<CouponIssue>>> searchIssues(
            @PathVariable UUID couponEventId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) IssueStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @PageableDefault(size = 20) Pageable pageable) {

        CouponIssueSearchCondition condition = new CouponIssueSearchCondition(
                couponEventId, userId, status, startDate, endDate);

        Page<CouponIssue> result = couponIssueQueryRepository.searchIssues(condition, pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{couponEventId}/stats")
    @Operation(summary = "발급 통계 조회", description = "쿠폰 이벤트의 발급 통계를 조회합니다.")
    public ResponseEntity<ApiResponse<CouponIssueStats>> getIssueStats(@PathVariable UUID couponEventId) {
        CouponIssueStats stats = couponIssueQueryRepository.getIssueStats(couponEventId);
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }
}
