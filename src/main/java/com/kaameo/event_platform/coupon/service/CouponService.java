package com.kaameo.event_platform.coupon.service;

import com.kaameo.event_platform.coupon.domain.CouponEvent;
import com.kaameo.event_platform.coupon.dto.CouponDetailResponse;
import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.repository.CouponEventRepository;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {

    private final CouponEventRepository couponEventRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponDetailResponse getCouponEvent(UUID couponEventId) {
        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new CouponNotFoundException("Coupon event not found: " + couponEventId));

        long issuedCount = couponIssueRepository.countByCouponEventId(couponEventId);
        long remainingStock = event.getTotalStock() - issuedCount;

        return new CouponDetailResponse(
                event.getId(),
                event.getName(),
                event.getTotalStock(),
                Math.max(remainingStock, 0),
                event.getStatus().name(),
                event.getStartAt(),
                event.getEndAt()
        );
    }
}
