package com.kaameo.event_platform.coupon.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.kaameo.event_platform.coupon.domain.CouponEvent;
import com.kaameo.event_platform.coupon.domain.CouponIssue;
import com.kaameo.event_platform.coupon.domain.IssueStatus;
import com.kaameo.event_platform.coupon.dto.CouponIssueRequest;
import com.kaameo.event_platform.coupon.dto.CouponIssueResponse;
import com.kaameo.event_platform.coupon.dto.CouponIssueStatusResponse;
import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.exception.CouponSoldOutException;
import com.kaameo.event_platform.coupon.exception.DuplicateIssueException;
import com.kaameo.event_platform.coupon.repository.CouponEventRepository;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponEventRepository couponEventRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Transactional
    public CouponIssueResponse issueCoupon(CouponIssueRequest request, String idempotencyKey) {
        return couponIssueRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> new CouponIssueResponse(
                        existing.getRequestId(),
                        existing.getStatus().name(),
                        "이미 처리된 요청입니다."
                ))
                .orElseGet(() -> processNewIssue(request, idempotencyKey));
    }

    private CouponIssueResponse processNewIssue(CouponIssueRequest request, String idempotencyKey) {
        CouponEvent event = couponEventRepository.findById(request.couponEventId())
                .orElseThrow(() -> new CouponNotFoundException("Coupon event not found: " + request.couponEventId()));

        if (!event.isActive()) {
            throw new CouponNotFoundException("Coupon event is not active: " + request.couponEventId());
        }

        if (couponIssueRepository.existsByCouponEventIdAndUserId(request.couponEventId(), request.userId())) {
            throw new DuplicateIssueException("이미 발급된 쿠폰입니다.");
        }

        long issuedCount = couponIssueRepository.countByCouponEventId(request.couponEventId());
        if (issuedCount >= event.getTotalStock()) {
            throw new CouponSoldOutException("쿠폰이 모두 소진되었습니다.");
        }

        UUID requestId = UuidCreator.getTimeOrderedEpoch();
        CouponIssue issue = CouponIssue.builder()
                .requestId(requestId)
                .couponEventId(request.couponEventId())
                .userId(request.userId())
                .idempotencyKey(idempotencyKey)
                .status(IssueStatus.ISSUED)
                .build();
        issue.markIssued();

        couponIssueRepository.save(issue);

        return new CouponIssueResponse(
                requestId,
                IssueStatus.ISSUED.name(),
                "쿠폰이 발급되었습니다."
        );
    }

    @Transactional(readOnly = true)
    public CouponIssueStatusResponse getIssueStatus(UUID requestId) {
        CouponIssue issue = couponIssueRepository.findByRequestId(requestId)
                .orElseThrow(() -> new CouponNotFoundException("Issue request not found: " + requestId));

        return new CouponIssueStatusResponse(
                issue.getRequestId(),
                issue.getCouponEventId(),
                issue.getUserId(),
                issue.getStatus().name(),
                issue.getIssuedAt()
        );
    }
}
