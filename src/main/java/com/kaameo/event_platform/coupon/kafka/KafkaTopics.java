package com.kaameo.event_platform.coupon.kafka;

public final class KafkaTopics {

    public static final String COUPON_ISSUE = "coupon-issue";
    public static final String COUPON_ISSUE_DLQ = "coupon-issue-dlq";

    public static final String GROUP_COUPON_ISSUE = "coupon-issue-group";

    private KafkaTopics() {
    }
}
