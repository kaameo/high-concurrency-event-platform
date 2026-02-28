# Phase 2 아키텍처 — Redis + Kafka 비동기 파이프라인

## 시스템 아키텍처

```mermaid
graph TB
    subgraph Client
        K6[k6 Load Test<br/>100 VUs]
    end

    subgraph "Spring Boot Application"
        CC[CouponController<br/>POST /api/v1/coupons/issue]
        CIS[CouponIssueService]
        IM[RedisIdempotencyManager]
        RL[RedisRateLimiter]
        DC[RedisDuplicateChecker]
        SM[RedisStockManager]
        KP[CouponIssueProducer]
        KC[CouponIssueConsumer]
        CIR[CouponIssueRepository]
    end

    subgraph "Redis"
        RD[(Redis<br/>재고 · 멱등성 · 중복 · Rate Limit)]
    end

    subgraph "Kafka"
        KT[coupon-issue<br/>3 partitions]
        DLQ[coupon-issue-dlq<br/>1 partition]
    end

    subgraph "Database"
        PG[(PostgreSQL<br/>coupon_issue)]
    end

    K6 -->|HTTP POST| CC
    CC --> CIS
    CIS -->|1. SET NX| IM
    CIS -->|2. ZSET Sliding Window| RL
    CIS -->|3. SET NX| DC
    CIS -->|4. DECR 원자적| SM
    IM --> RD
    RL --> RD
    DC --> RD
    SM --> RD
    CIS -->|5. send| KP
    KP --> KT
    CIS -->|202 Accepted| CC
    CC -->|Response| K6

    KC -->|consume| KT
    KC -->|DB INSERT| CIR
    CIR --> PG
    KC -.->|실패 시| DLQ

    style RD fill:#DC382D,color:#fff
    style PG fill:#336791,color:#fff
    style KT fill:#231F20,color:#fff
    style DLQ fill:#555,color:#fff
    style CC fill:#6DB33F,color:#fff
    style K6 fill:#7D64FF,color:#fff
```

## 요청 처리 흐름

```mermaid
sequenceDiagram
    participant C as Client
    participant API as CouponController
    participant SVC as CouponIssueService
    participant Redis as Redis
    participant Kafka as Kafka
    participant Consumer as KafkaConsumer
    participant DB as PostgreSQL

    C->>API: POST /api/v1/coupons/issue<br/>{couponEventId, userId}<br/>Idempotency-Key: uuid

    API->>SVC: issueCoupon()

    SVC->>Redis: SET idempotency:{key} NX TTL 24h
    alt 이미 존재
        Redis-->>SVC: false
        SVC-->>API: IdempotencyKeyConflictException
        API-->>C: 409 Conflict
    end

    SVC->>Redis: ZRANGEBYSCORE rate_limit:{userId}
    alt 초과
        SVC-->>API: RateLimitExceededException
        API-->>C: 429 Too Many Requests
    end

    SVC->>Redis: SET coupon:{eventId}:issued:{userId} NX
    alt 이미 발급
        SVC-->>API: DuplicateCouponException
        API-->>C: 409 Conflict
    end

    SVC->>Redis: DECR coupon:stock:{eventId}
    alt 결과 < 0
        SVC->>Redis: INCR coupon:stock:{eventId}
        SVC-->>API: CouponSoldOutException
        API-->>C: 410 Gone
    end

    SVC->>Kafka: produce(coupon-issue, message)
    SVC-->>API: CouponIssueResponse (PENDING)
    API-->>C: 202 Accepted

    Note over Kafka,DB: 비동기 처리

    Kafka->>Consumer: poll message
    Consumer->>DB: INSERT coupon_issue (ISSUED)
    alt 실패 (3회 재시도 후)
        Consumer->>Kafka: DLQ 전송
    end
```

## Kafka Consumer 에러 핸들링

```mermaid
flowchart TD
    MSG[메시지 수신] --> PROC[DB INSERT 시도]
    PROC -->|성공| ACK[Commit Offset]
    PROC -->|실패| R1[재시도 1<br/>1초 대기]
    R1 -->|성공| ACK
    R1 -->|실패| R2[재시도 2<br/>2초 대기]
    R2 -->|성공| ACK
    R2 -->|실패| R3[재시도 3<br/>4초 대기]
    R3 -->|성공| ACK
    R3 -->|실패| DLQ[DLQ 전송<br/>coupon-issue-dlq]
    DLQ --> ACK

    style ACK fill:#27ae60,color:#fff
    style DLQ fill:#e74c3c,color:#fff
    style R1 fill:#f39c12,color:#fff
    style R2 fill:#f39c12,color:#fff
    style R3 fill:#f39c12,color:#fff
```

## Phase 1 → Phase 2 비교

```mermaid
graph LR
    subgraph "Phase 1 — 동기 DB 직결"
        A1[API] -->|COUNT| D1[(DB)]
        A1 -->|CHECK| D1
        A1 -->|INSERT| D1
        A1 -->|200 OK| R1[Response]
    end

    subgraph "Phase 2 — Redis + Kafka 비동기"
        A2[API] -->|DECR 원자적| RD2[(Redis)]
        A2 -->|produce| KF2[Kafka]
        A2 -->|202 Accepted| R2[Response]
        KF2 -->|consume| D2[(DB)]
    end

    style D1 fill:#e74c3c,color:#fff
    style RD2 fill:#27ae60,color:#fff
    style KF2 fill:#27ae60,color:#fff
    style D2 fill:#336791,color:#fff
```

## 성능 요약

| 지표 | Phase 1 | Phase 2 | 변화 |
|------|---------|---------|------|
| 평균 RPS | ~530 | 543 | +2.5% |
| 실제 발급 | 100,004 | **100,000** | 정확 |
| 초과 발급 | **4건** | **0건** | 해결 |
| Latency avg | — | 5.44ms | — |
| Latency p95 | — | 12.04ms | — |
| Redis ↔ DB 정합성 | N/A | 100% | — |
| 에러 | 0 | 0 | — |
