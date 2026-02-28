package com.kaameo.event_platform.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaameo.event_platform.coupon.controller.CouponController;
import com.kaameo.event_platform.coupon.dto.*;
import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.exception.CouponSoldOutException;
import com.kaameo.event_platform.coupon.exception.DuplicateIssueException;
import com.kaameo.event_platform.coupon.service.CouponIssueService;
import com.kaameo.event_platform.coupon.service.CouponService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CouponController.class)
class CouponControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private CouponService couponService;

    @MockitoBean
    private CouponIssueService couponIssueService;

    private static final UUID EVENT_ID = UUID.fromString("019577a0-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("019577a0-0000-7000-8000-000000000002");

    @Test
    void issueCoupon_returns202() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);
        CouponIssueResponse response = new CouponIssueResponse(
                REQUEST_ID, "ISSUED", "쿠폰이 발급되었습니다.");

        given(couponIssueService.issueCoupon(any(), eq(idempotencyKey))).willReturn(response);

        mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ISSUED"));
    }

    @Test
    void issueCoupon_soldOut_returns410() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(couponIssueService.issueCoupon(any(), eq(idempotencyKey)))
                .willThrow(new CouponSoldOutException("쿠폰이 모두 소진되었습니다."));

        mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void issueCoupon_duplicate_returns409() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CouponIssueRequest request = new CouponIssueRequest(EVENT_ID, 12345L);

        given(couponIssueService.issueCoupon(any(), eq(idempotencyKey)))
                .willThrow(new DuplicateIssueException("이미 발급된 쿠폰입니다."));

        mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getIssueStatus_returns200() throws Exception {
        CouponIssueStatusResponse response = new CouponIssueStatusResponse(
                REQUEST_ID, EVENT_ID, 12345L, "ISSUED", LocalDateTime.now());

        given(couponIssueService.getIssueStatus(REQUEST_ID)).willReturn(response);

        mockMvc.perform(get("/api/v1/coupons/requests/{requestId}", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ISSUED"));
    }

    @Test
    void getCouponEvent_returns200() throws Exception {
        CouponDetailResponse response = new CouponDetailResponse(
                EVENT_ID, "신규 가입 쿠폰", 100000, 45230L, "ACTIVE",
                LocalDateTime.now(), LocalDateTime.now().plusDays(1));

        given(couponService.getCouponEvent(EVENT_ID)).willReturn(response);

        mockMvc.perform(get("/api/v1/coupons/{couponEventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("신규 가입 쿠폰"));
    }

    @Test
    void getCouponEvent_notFound_returns404() throws Exception {
        UUID unknownId = UUID.fromString("019577a0-0000-7000-8000-ffffffffffff");
        given(couponService.getCouponEvent(unknownId))
                .willThrow(new CouponNotFoundException("Coupon event not found: " + unknownId));

        mockMvc.perform(get("/api/v1/coupons/{couponEventId}", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
