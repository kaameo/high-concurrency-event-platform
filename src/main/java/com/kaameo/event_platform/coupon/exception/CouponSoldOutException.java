package com.kaameo.event_platform.coupon.exception;

public class CouponSoldOutException extends RuntimeException {
    public CouponSoldOutException(String message) {
        super(message);
    }
}
