package com.kaameo.event_platform.coupon;

import com.kaameo.event_platform.coupon.domain.CouponEvent;
import com.kaameo.event_platform.coupon.domain.CouponEventStatus;
import com.kaameo.event_platform.coupon.domain.CouponIssue;
import com.kaameo.event_platform.coupon.domain.IssueStatus;
import com.kaameo.event_platform.coupon.dto.CouponIssueRequest;
import com.kaameo.event_platform.coupon.dto.CouponIssueResponse;
import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.exception.CouponSoldOutException;
import com.kaameo.event_platform.coupon.exception.DuplicateIssueException;
import com.kaameo.event_platform.coupon.repository.CouponEventRepository;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import com.kaameo.event_platform.coupon.service.CouponIssueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CouponIssueServiceTest {

    @Mock
    private CouponEventRepository couponEventRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @InjectMocks
    private CouponIssueService couponIssueService;

    private static final UUID EVENT_ID = UUID.fromString("019577a0-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("019577a0-0000-7000-8000-000000000002");

    @Test
    void issueCoupon_success() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);
        CouponEvent event = CouponEvent.builder()
                .name("Test Coupon")
                .totalStock(100)
                .status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1))
                .endAt(LocalDateTime.now().plusHours(1))
                .build();

        given(couponIssueRepository.findByIdempotencyKey(idempotencyKey)).willReturn(Optional.empty());
        given(couponEventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
        given(couponIssueRepository.existsByCouponEventIdAndUserId(EVENT_ID, 12345L)).willReturn(false);
        given(couponIssueRepository.countByCouponEventId(EVENT_ID)).willReturn(0L);
        given(couponIssueRepository.save(any(CouponIssue.class))).willAnswer(inv -> inv.getArgument(0));

        CouponIssueResponse response = couponIssueService.issueCoupon(request, idempotencyKey);

        assertThat(response.status()).isEqualTo("ISSUED");
        assertThat(response.requestId()).isNotNull();
    }

    @Test
    void issueCoupon_idempotent_returnsExisting() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        CouponIssue existing = CouponIssue.builder()
                .requestId(REQUEST_ID)
                .couponEventId(EVENT_ID)
                .userId(12345L)
                .idempotencyKey(idempotencyKey)
                .status(IssueStatus.ISSUED)
                .build();

        given(couponIssueRepository.findByIdempotencyKey(idempotencyKey)).willReturn(Optional.of(existing));

        CouponIssueResponse response = couponIssueService.issueCoupon(request, idempotencyKey);

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
    }

    @Test
    void issueCoupon_eventNotFound_throwsException() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(couponIssueRepository.findByIdempotencyKey(idempotencyKey)).willReturn(Optional.empty());
        given(couponEventRepository.findById(EVENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> couponIssueService.issueCoupon(request, idempotencyKey))
                .isInstanceOf(CouponNotFoundException.class);
    }

    @Test
    void issueCoupon_duplicate_throwsException() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);
        CouponEvent event = CouponEvent.builder()
                .name("Test").totalStock(100).status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1)).endAt(LocalDateTime.now().plusHours(1))
                .build();

        given(couponIssueRepository.findByIdempotencyKey(idempotencyKey)).willReturn(Optional.empty());
        given(couponEventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
        given(couponIssueRepository.existsByCouponEventIdAndUserId(EVENT_ID, 12345L)).willReturn(true);

        assertThatThrownBy(() -> couponIssueService.issueCoupon(request, idempotencyKey))
                .isInstanceOf(DuplicateIssueException.class);
    }

    @Test
    void issueCoupon_soldOut_throwsException() {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);
        CouponEvent event = CouponEvent.builder()
                .name("Test").totalStock(100).status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1)).endAt(LocalDateTime.now().plusHours(1))
                .build();

        given(couponIssueRepository.findByIdempotencyKey(idempotencyKey)).willReturn(Optional.empty());
        given(couponEventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
        given(couponIssueRepository.existsByCouponEventIdAndUserId(EVENT_ID, 12345L)).willReturn(false);
        given(couponIssueRepository.countByCouponEventId(EVENT_ID)).willReturn(100L);

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
