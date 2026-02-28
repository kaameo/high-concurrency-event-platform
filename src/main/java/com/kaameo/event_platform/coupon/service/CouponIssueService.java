package com.kaameo.event_platform.coupon.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.kaameo.event_platform.coupon.dto.CouponIssueRequest;
import com.kaameo.event_platform.coupon.dto.CouponIssueResponse;
import com.kaameo.event_platform.coupon.dto.CouponIssueStatusResponse;
import com.kaameo.event_platform.coupon.domain.CouponIssue;
import com.kaameo.event_platform.coupon.domain.IssueStatus;
import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.exception.CouponSoldOutException;
import com.kaameo.event_platform.coupon.exception.DuplicateIssueException;
import com.kaameo.event_platform.coupon.exception.RateLimitExceededException;
import com.kaameo.event_platform.coupon.kafka.CouponIssueProducer;
import com.kaameo.event_platform.coupon.message.CouponIssueMessage;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponIssueRepository couponIssueRepository;
    private final RedisIdempotencyManager redisIdempotencyManager;
    private final RedisRateLimiter redisRateLimiter;
    private final RedisDuplicateChecker redisDuplicateChecker;
    private final RedisStockManager redisStockManager;
    private final CouponIssueProducer couponIssueProducer;

    private static final int RATE_LIMIT_MAX_REQUESTS = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(1);

    public CouponIssueResponse issueCoupon(CouponIssueRequest request, String idempotencyKey) {
        UUID requestId = UuidCreator.getTimeOrderedEpoch();

        if (!redisIdempotencyManager.checkAndSet(idempotencyKey, requestId)) {
            return new CouponIssueResponse(null, IssueStatus.PENDING.name(), "이미 처리된 요청입니다.");
        }

        if (!redisRateLimiter.isAllowed(request.userId(), RATE_LIMIT_MAX_REQUESTS, RATE_LIMIT_WINDOW)) {
            throw new RateLimitExceededException("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        }

        if (!redisDuplicateChecker.checkAndMark(request.couponEventId(), request.userId())) {
            throw new DuplicateIssueException("이미 발급된 쿠폰입니다.");
        }

        if (!redisStockManager.decrementStock(request.couponEventId())) {
            throw new CouponSoldOutException("쿠폰이 모두 소진되었습니다.");
        }

        CouponIssueMessage message = new CouponIssueMessage(
                requestId,
                request.couponEventId(),
                request.userId(),
                idempotencyKey,
                LocalDateTime.now()
        );
        couponIssueProducer.send(message);

        return new CouponIssueResponse(requestId, IssueStatus.PENDING.name(), "쿠폰 발급 요청이 접수되었습니다.");
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
