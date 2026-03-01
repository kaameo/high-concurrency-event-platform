package com.kaameo.event_platform.coupon;

import com.kaameo.event_platform.coupon.domain.CouponIssue;
import com.kaameo.event_platform.coupon.domain.IssueStatus;
import com.kaameo.event_platform.coupon.dto.CouponIssueRequest;
import com.kaameo.event_platform.coupon.dto.CouponIssueResponse;
import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.exception.CouponSoldOutException;
import com.kaameo.event_platform.coupon.exception.DuplicateIssueException;
import com.kaameo.event_platform.coupon.exception.RateLimitExceededException;
import com.kaameo.event_platform.coupon.kafka.CouponIssueProducer;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import com.kaameo.event_platform.coupon.service.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponIssueServiceTest {

    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private RedisIdempotencyManager redisIdempotencyManager;
    @Mock
    private RedisRateLimiter redisRateLimiter;
    @Mock
    private RedisDuplicateChecker redisDuplicateChecker;
    @Mock
    private RedisStockManager redisStockManager;
    @Mock
    private CouponIssueProducer couponIssueProducer;

    private CouponIssueService couponIssueService;

    @BeforeEach
    void setUp() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("couponIssue");
        couponIssueService = new CouponIssueService(
                couponIssueRepository,
                redisIdempotencyManager,
                redisRateLimiter,
                redisDuplicateChecker,
                redisStockManager,
                couponIssueProducer,
                circuitBreaker
        );
    }

    private static final UUID EVENT_ID = UUID.fromString("019577a0-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("019577a0-0000-7000-8000-000000000002");

    @Test
    void issueCoupon_success_returnsPending() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(redisIdempotencyManager.checkAndSet(eq(idempotencyKey), any(UUID.class))).willReturn(true);
        given(redisRateLimiter.isAllowed(eq(12345L), eq(5), any(Duration.class))).willReturn(true);
        given(redisDuplicateChecker.checkAndMark(EVENT_ID, 12345L)).willReturn(true);
        given(redisStockManager.decrementStock(EVENT_ID)).willReturn(true);

        CouponIssueResponse response = couponIssueService.issueCoupon(request, idempotencyKey);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.requestId()).isNotNull();
        verify(couponIssueProducer).send(any());
    }

    @Test
    void issueCoupon_duplicateIdempotencyKey_returnsExisting() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(redisIdempotencyManager.checkAndSet(eq(idempotencyKey), any(UUID.class))).willReturn(false);

        CouponIssueResponse response = couponIssueService.issueCoupon(request, idempotencyKey);

        assertThat(response.message()).isEqualTo("이미 처리된 요청입니다.");
    }

    @Test
    void issueCoupon_rateLimitExceeded_throwsException() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(redisIdempotencyManager.checkAndSet(eq(idempotencyKey), any(UUID.class))).willReturn(true);
        given(redisRateLimiter.isAllowed(eq(12345L), eq(5), any(Duration.class))).willReturn(false);

        assertThatThrownBy(() -> couponIssueService.issueCoupon(request, idempotencyKey))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void issueCoupon_duplicate_throwsException() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(redisIdempotencyManager.checkAndSet(eq(idempotencyKey), any(UUID.class))).willReturn(true);
        given(redisRateLimiter.isAllowed(eq(12345L), eq(5), any(Duration.class))).willReturn(true);
        given(redisDuplicateChecker.checkAndMark(EVENT_ID, 12345L)).willReturn(false);

        assertThatThrownBy(() -> couponIssueService.issueCoupon(request, idempotencyKey))
                .isInstanceOf(DuplicateIssueException.class);
    }

    @Test
    void issueCoupon_soldOut_throwsException() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(redisIdempotencyManager.checkAndSet(eq(idempotencyKey), any(UUID.class))).willReturn(true);
        given(redisRateLimiter.isAllowed(eq(12345L), eq(5), any(Duration.class))).willReturn(true);
        given(redisDuplicateChecker.checkAndMark(EVENT_ID, 12345L)).willReturn(true);
        given(redisStockManager.decrementStock(EVENT_ID)).willReturn(false);

        assertThatThrownBy(() -> couponIssueService.issueCoupon(request, idempotencyKey))
                .isInstanceOf(CouponSoldOutException.class);
    }

    @Test
    void getIssueStatus_success() {
        CouponIssue issue = CouponIssue.builder()
                .requestId(REQUEST_ID).couponEventId(EVENT_ID).userId(12345L)
                .idempotencyKey("key").status(IssueStatus.ISSUED).build();

        given(couponIssueRepository.findByRequestId(REQUEST_ID)).willReturn(Optional.of(issue));

        var response = couponIssueService.getIssueStatus(REQUEST_ID);

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.status()).isEqualTo("ISSUED");
    }

    @Test
    void getIssueStatus_notFound_throwsException() {
        UUID unknownId = UUID.fromString("019577a0-0000-7000-8000-ffffffffffff");
        given(couponIssueRepository.findByRequestId(unknownId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> couponIssueService.getIssueStatus(unknownId))
                .isInstanceOf(CouponNotFoundException.class);
    }
}
