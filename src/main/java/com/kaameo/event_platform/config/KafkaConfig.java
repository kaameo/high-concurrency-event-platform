package com.kaameo.event_platform.config;

import com.kaameo.event_platform.coupon.kafka.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic couponIssueTopic() {
        return TopicBuilder.name(KafkaTopics.COUPON_ISSUE)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic couponIssueDlqTopic() {
        return TopicBuilder.name(KafkaTopics.COUPON_ISSUE_DLQ)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(KafkaTopics.COUPON_ISSUE_DLQ, 0));

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(7000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
