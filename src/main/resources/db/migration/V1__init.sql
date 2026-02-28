CREATE TABLE coupon_event (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    total_stock INTEGER NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_at    TIMESTAMP NOT NULL,
    end_at      TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE coupon_issue (
    id               BIGSERIAL PRIMARY KEY,
    request_id       UUID NOT NULL UNIQUE,
    coupon_event_id  BIGINT NOT NULL REFERENCES coupon_event(id),
    user_id          BIGINT NOT NULL,
    idempotency_key  VARCHAR(64) NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    issued_at        TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (coupon_event_id, user_id),
    UNIQUE (idempotency_key)
);

CREATE INDEX idx_coupon_issue_request_id ON coupon_issue(request_id);
CREATE INDEX idx_coupon_issue_coupon_event_id ON coupon_issue(coupon_event_id);
CREATE INDEX idx_coupon_issue_user_id ON coupon_issue(user_id);
CREATE INDEX idx_coupon_issue_status ON coupon_issue(status);
