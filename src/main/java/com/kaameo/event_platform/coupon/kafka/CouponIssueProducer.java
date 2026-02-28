package com.kaameo.event_platform.coupon.kafka;

import com.kaameo.event_platform.coupon.message.CouponIssueMessage;
import com.kaameo.event_platform.coupon.service.RedisStockManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueProducer {

    private final KafkaTemplate<String, CouponIssueMessage> kafkaTemplate;
    private final RedisStockManager redisStockManager;

    public void send(CouponIssueMessage message) {
        kafkaTemplate.send(KafkaTopics.COUPON_ISSUE, message.requestId().toString(), message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 전송 실패, 재고 복원: requestId={}", message.requestId(), ex);
                        redisStockManager.restoreStock(message.couponEventId());
                    } else {
                        log.debug("Kafka 전송 성공: requestId={}, offset={}",
                                message.requestId(), result.getRecordMetadata().offset());
                    }
                });
    }
}
