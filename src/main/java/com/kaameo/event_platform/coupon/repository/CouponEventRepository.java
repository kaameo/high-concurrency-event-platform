package com.kaameo.event_platform.coupon.repository;

import com.kaameo.event_platform.coupon.domain.CouponEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CouponEventRepository extends JpaRepository<CouponEvent, UUID> {
}
