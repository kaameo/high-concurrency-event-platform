# ADR-004: Idempotency-Key 기반 중복 방지

- **Status**: Accepted
- **Date**: 2026-02-28
- **Deciders**: 개발팀

## Context

쿠폰 발급 API는 네트워크 재시도, 클라이언트 중복 클릭 등으로 인해 동일 요청이 여러 번 도달할 수 있다. 멱등성(Idempotency)을 보장하여 중복 발급을 방지해야 한다.

### 이중 방어가 필요한 이유
- **같은 사용자가 같은 쿠폰을 중복 요청**: 비즈니스 규칙 위반 → `(coupon_event_id, user_id)` UNIQUE
- **네트워크 재시도로 동일 요청 중복 도달**: 기술적 중복 → Idempotency-Key로 방지

## Decision Drivers

- 업계 표준 패턴 준수 (Stripe, Toss 등 결제 시스템)
- 구현 복잡도 최소화 (Phase 1)
- Phase 2 비동기 전환 시에도 동작 보장

## Considered Options

### Option 1: DB UNIQUE 제약만 사용
- `(coupon_event_id, user_id)` UNIQUE로 비즈니스 중복 방지
- 네트워크 재시도 구분 불가 (다른 Idempotency-Key로 같은 요청 시)
- `DataIntegrityViolationException` → 500 에러

### Option 2: Redis 기반 Idempotency (TTL)
- Redis에 `idempotency_key → response` 캐싱 (TTL 24h)
- Redis 장애 시 멱등성 보장 불가
- Phase 1에서 Redis 의존 추가

### Option 3: Idempotency-Key 헤더 + DB UNIQUE ✅ 선택
- 클라이언트가 `Idempotency-Key` 헤더로 요청 고유 식별자 전달
- DB `idempotency_key` 컬럼 UNIQUE 제약
- 기존 요청 존재 시 저장된 결과 반환 (재처리 없음)

## Decision

**Idempotency-Key HTTP 헤더 패턴을 채택하고, DB UNIQUE 제약으로 영구 보장한다.**

### 구현 상세

#### 요청 흐름
```
1. POST /api/v1/coupons/issue (Idempotency-Key: "abc-123")
2. couponIssueRepository.findByIdempotencyKey("abc-123")
   → 존재: 기존 CouponIssueResponse 반환 (재처리 없음)
   → 미존재: 신규 발급 처리 → CouponIssue 저장 (idempotency_key = "abc-123")
3. 202 Accepted 응답
```

#### DB 스키마
```sql
CREATE TABLE coupon_issue (
    ...
    idempotency_key VARCHAR(64) NOT NULL,
    UNIQUE (idempotency_key),          -- 멱등성 보장
    UNIQUE (coupon_event_id, user_id)  -- 비즈니스 중복 방지
);
```

#### 이중 방어 매트릭스

| 시나리오 | 방어 레이어 | 결과 |
|---------|-----------|------|
| 같은 Key로 재시도 | `findByIdempotencyKey` | 기존 결과 반환 (200) |
| 다른 Key로 같은 쿠폰 재요청 | `existsByCouponEventIdAndUserId` | 409 Conflict |
| 동시에 같은 Key 도착 | DB UNIQUE 제약 | 하나 성공, 나머지 실패 |

### 알려진 이슈

**동시성 충돌 미처리 (P0)**

동시에 같은 Idempotency-Key로 요청이 도착하면:
1. 두 요청 모두 `findByIdempotencyKey`에서 `empty`
2. 두 요청 모두 INSERT 시도
3. UNIQUE 제약 위반 → `DataIntegrityViolationException` → 500 에러

**해결 계획**: `DataIntegrityViolationException` catch 후 기존 레코드 재조회하여 반환.

## Consequences

### Positive
- 업계 표준 패턴 (Stripe, Toss, 카카오페이 등)
- DB UNIQUE로 영구 보장 (Redis TTL 만료 위험 없음)
- 비즈니스 중복과 기술적 중복을 분리하여 명확한 에러 코드 제공
- Phase 2 비동기 전환 시에도 동일 패턴 유지 가능

### Negative
- Idempotency-Key 생성 책임이 클라이언트에 있음
- 키 미전송 시 400 에러 (UX 고려 필요)
- 영구 저장으로 인한 DB 레코드 증가 (정리 정책 필요)

### Risks
- 클라이언트가 매 요청마다 새 Key 생성 시 멱등성 무의미 → API 문서에 사용 가이드 명시
- Idempotency-Key 충돌 시 500 에러 → 예외 핸들링 추가 필요 (Phase 1 P0)

## References

- [IETF Draft: The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [Stripe API: Idempotent Requests](https://stripe.com/docs/api/idempotent_requests)
- [Toss Payments: 멱등키](https://docs.tosspayments.com/reference/using-api/idempotency-key)
