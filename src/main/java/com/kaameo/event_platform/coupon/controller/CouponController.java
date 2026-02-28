package com.kaameo.event_platform.coupon.controller;

import com.kaameo.event_platform.common.dto.ApiResponse;
import com.kaameo.event_platform.coupon.dto.CouponDetailResponse;
import com.kaameo.event_platform.coupon.dto.CouponIssueRequest;
import com.kaameo.event_platform.coupon.dto.CouponIssueResponse;
import com.kaameo.event_platform.coupon.dto.CouponIssueStatusResponse;
import com.kaameo.event_platform.coupon.service.CouponIssueService;
import com.kaameo.event_platform.coupon.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
@Tag(name = "Coupon", description = "쿠폰 발급 API")
public class CouponController {

    private final CouponService couponService;
    private final CouponIssueService couponIssueService;

    @PostMapping("/issue")
    @Operation(summary = "쿠폰 발급 요청", description = "선착순 쿠폰 발급을 요청합니다.")
    public ResponseEntity<ApiResponse<CouponIssueResponse>> issueCoupon(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CouponIssueRequest request) {
        CouponIssueResponse response = couponIssueService.issueCoupon(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    @GetMapping("/requests/{requestId}")
    @Operation(summary = "발급 요청 상태 조회", description = "쿠폰 발급 요청의 처리 상태를 조회합니다.")
    public ResponseEntity<ApiResponse<CouponIssueStatusResponse>> getIssueStatus(
            @PathVariable UUID requestId) {
        CouponIssueStatusResponse response = couponIssueService.getIssueStatus(requestId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{couponEventId}")
    @Operation(summary = "쿠폰 이벤트 상세 조회", description = "쿠폰 이벤트의 상세 정보를 조회합니다.")
    public ResponseEntity<ApiResponse<CouponDetailResponse>> getCouponEvent(
            @PathVariable UUID couponEventId) {
        CouponDetailResponse response = couponService.getCouponEvent(couponEventId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
