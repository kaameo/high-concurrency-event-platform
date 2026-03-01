package com.kaameo.event_platform.config;

import com.kaameo.event_platform.coupon.exception.CouponNotFoundException;
import com.kaameo.event_platform.coupon.exception.CouponSoldOutException;
import com.kaameo.event_platform.coupon.exception.DuplicateIssueException;
import com.kaameo.event_platform.coupon.exception.RateLimitExceededException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CircuitBreakerConfig {

    @Bean
    public CircuitBreaker couponIssueCircuitBreaker() {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowType(SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .slowCallDurationThreshold(Duration.ofSeconds(3))
                        .slowCallRateThreshold(80)
                        .ignoreExceptions(
                                CouponSoldOutException.class,
                                CouponNotFoundException.class,
                                DuplicateIssueException.class,
                                RateLimitExceededException.class
                        )
                        .build();

        return CircuitBreaker.of("couponIssue", config);
    }
}
