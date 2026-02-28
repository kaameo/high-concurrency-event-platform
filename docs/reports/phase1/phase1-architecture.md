# Phase 1 아키텍처 — 동기 DB 직결 방식

## 시스템 아키텍처

```mermaid
graph TB
    subgraph Client
        K6[k6 Load Test<br/>100 VUs]
    end

    subgraph "Spring Boot Application"
        CC[CouponController<br/>POST /api/v1/coupons/issue]
        CIS[CouponIssueService]
        CIR[CouponIssueRepository<br/>JPA]
        CER[CouponEventRepository<br/>JPA]
    end

    subgraph "Database"
        PG[(PostgreSQL<br/>coupon_event<br/>coupon_issue)]
    end

    K6 -->|HTTP POST| CC
    CC --> CIS
    CIS -->|1. COUNT 발급 건수| CIR
    CIS -->|2. CHECK 재고| CER
    CIS -->|3. INSERT 발급| CIR
    CIR --> PG
    CER --> PG
    CIS -->|200 OK| CC
    CC -->|Response| K6

    style PG fill:#336791,color:#fff
    style CC fill:#6DB33F,color:#fff
    style K6 fill:#7D64FF,color:#fff
```

## 요청 처리 흐름

```mermaid
sequenceDiagram
    participant C as Client
    participant API as CouponController
    participant SVC as CouponIssueService
    participant DB as PostgreSQL

    C->>API: POST /api/v1/coupons/issue<br/>{couponEventId, userId}
    API->>SVC: issueCoupon()

    SVC->>DB: SELECT COUNT(*) FROM coupon_issue<br/>WHERE coupon_event_id = ?
    DB-->>SVC: issuedCount

    alt issuedCount >= totalStock
        SVC-->>API: CouponSoldOutException
        API-->>C: 410 Gone
    else 재고 있음
        SVC->>DB: SELECT * FROM coupon_event<br/>WHERE id = ?
        DB-->>SVC: CouponEvent

        SVC->>DB: INSERT INTO coupon_issue<br/>(id, coupon_event_id, user_id, status)
        DB-->>SVC: saved

        SVC-->>API: CouponIssueResponse
        API-->>C: 200 OK (ISSUED)
    end
```

## 문제점

```mermaid
graph LR
    subgraph "Race Condition 발생 구간"
        R[READ<br/>COUNT 조회] --> C[CHECK<br/>재고 비교] --> A[ACT<br/>INSERT]
    end

    T1[Thread 1] -->|동시| R
    T2[Thread 2] -->|동시| R

    R -->|둘 다 99건| C
    C -->|둘 다 통과| A
    A -->|100,004건 발급| OVER[초과 발급 4건]

    style OVER fill:#e74c3c,color:#fff
    style R fill:#f39c12,color:#fff
    style C fill:#f39c12,color:#fff
    style A fill:#f39c12,color:#fff
```

## 성능 요약

| 지표 | 값 |
|------|-----|
| 평균 RPS | ~530 |
| 실제 발급 | 100,004건 |
| 초과 발급 | **4건 (Race Condition)** |
| 병목 지점 | DB check-then-act 비원자적 패턴 |
