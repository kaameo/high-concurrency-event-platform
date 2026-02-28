package com.kaameo.event_platform.coupon.kafka;

import com.kaameo.event_platform.coupon.domain.CouponIssue;
import com.kaameo.event_platform.coupon.domain.IssueStatus;
import com.kaameo.event_platform.coupon.message.CouponIssueMessage;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueConsumer {

    private final CouponIssueRepository couponIssueRepository;

    @KafkaListener(topics = KafkaTopics.COUPON_ISSUE, groupId = KafkaTopics.GROUP_COUPON_ISSUE)
    @Transactional
    public void consume(CouponIssueMessage message) {
        log.info("쿠폰 발급 메시지 수신: requestId={}, userId={}", message.requestId(), message.userId());

        try {
            if (couponIssueRepository.findByRequestId(message.requestId()).isPresent()) {
                log.warn("이미 처리된 요청: requestId={}", message.requestId());
                return;
            }

            CouponIssue issue = CouponIssue.builder()
                    .requestId(message.requestId())
                    .couponEventId(message.couponEventId())
                    .userId(message.userId())
                    .idempotencyKey(message.idempotencyKey())
                    .status(IssueStatus.PENDING)
                    .build();
            issue.markIssued();

            couponIssueRepository.save(issue);
            log.info("쿠폰 발급 완료: requestId={}", message.requestId());
        } catch (DataIntegrityViolationException e) {
            log.warn("중복 저장 시도 (무시): requestId={}", message.requestId());
        }
    }
}
