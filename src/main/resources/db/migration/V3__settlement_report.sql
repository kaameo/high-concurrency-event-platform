CREATE TABLE settlement_report (
    id              uuid PRIMARY KEY,
    coupon_event_id uuid        NOT NULL,
    total_issued    bigint      NOT NULL DEFAULT 0,
    total_pending   bigint      NOT NULL DEFAULT 0,
    total_failed    bigint      NOT NULL DEFAULT 0,
    redis_remaining bigint      NOT NULL DEFAULT 0,
    db_issued_count bigint      NOT NULL DEFAULT 0,
    is_consistent   boolean     NOT NULL DEFAULT true,
    settled_at      timestamp   NOT NULL,
    created_at      timestamp   NOT NULL DEFAULT now()
);

CREATE INDEX idx_settlement_report_coupon_event_id ON settlement_report (coupon_event_id);
CREATE INDEX idx_settlement_report_settled_at ON settlement_report (settled_at);
