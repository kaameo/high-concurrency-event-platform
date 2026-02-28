# ADR-005: Redis DECR 원자적 재고 차감 채택

- **Status**: Accepted
- **Date**: 2026-02-28
- **Deciders**: 개발팀
- **Supersedes**: [ADR-003](./003-sync-db-issuance.md) (Phase 1 동기 DB 발급)

## Context

Phase 1 baseline 부하 테스트에서 **Race Condition으로 4건 초과 발급** 확인. DB의 check-then-act 패턴 (`COUNT → CHECK → INSERT`)이 동시 요청에서 원자적이지 않아 재고를 초과하여 발급하는 문제.

```java
// Phase 1 — Race Condition 발생 코드
long issuedCount = couponIssueRepository.countByCouponEventId(eventId);  // READ
if (issuedCount >= event.getTotalStock())                                 // CHECK
    throw new CouponSoldOutException(...);
couponIssueRepository.save(issue);                                        // ACT
```

## Decision Drivers

- 동시 100+ VU 요청에서 초과 발급 0건 보장
- 단일 요청 레이턴시 최소화 (목표 <10ms)
- 수평 확장 가능한 구조
- 구현 복잡도 최소화

## Considered Options

### Option 1: Redis DECR 원자적 차감
```
DECR coupon:stock:{id}
  결과 >= 0 → 발급 허용
  결과 < 0  → INCR 복원 후 거부
```

### Option 2: DB SELECT FOR UPDATE (비관적 락)
```sql
SELECT total_stock FROM coupon_event WHERE id = ? FOR UPDATE;
-- 행 수준 락으로 직렬화
```

### Option 3: Redis Lua Script
```lua
local stock = redis.call('GET', key)
if tonumber(stock) > 0 then
    return redis.call('DECR', key)
end
return -1
```

### Option 4: Redisson 분산 락
```java
RLock lock = redisson.getLock("coupon:" + eventId);
lock.lock();
try { /* 재고 차감 */ } finally { lock.unlock(); }
```

## Decision

**Option 1: Redis DECR** 채택.

## Rationale

| 기준 | DECR | SELECT FOR UPDATE | Lua Script | Redisson Lock |
|------|------|-------------------|------------|---------------|
| 원자성 | O (단일 명령) | O (트랜잭션) | O (서버 실행) | O (분산 락) |
| 레이턴시 | ~0.1ms | ~2-5ms | ~0.1ms | ~1-3ms |
| DB 병목 | 없음 | **있음** (행 락 경합) | 없음 | 없음 |
| 구현 복잡도 | 낮음 | 낮음 | 중간 | 높음 |
| 음수 보정 필요 | **있음** (INCR 복원) | 없음 | 없음 | 없음 |
| 확장성 | 높음 | 낮음 (DB 제한) | 높음 | 중간 |

- DECR은 **단일 Redis 명령**으로 원자적 (Lua Script 불필요)
- 음수 보정(INCR)이 필요하지만, 극히 짧은 시간의 일시적 음수이므로 실제 문제 없음
- SELECT FOR UPDATE는 DB 커넥션 풀 병목 발생, 목표 RPS 달성 불가
- Lua Script는 DECR만으로 충분한 상황에서 과도한 복잡도
- Redisson 분산 락은 정상 발급 흐름에서 불필요 (재고 초기화 시에만 유용)

## Consequences

### 긍정적
- 초과 발급 0건 달성 (부하 테스트 검증 완료)
- DB 의존성 제거 → 레이턴시 대폭 감소
- 수평 확장 시 Redis만 확장하면 됨

### 부정적
- Redis 장애 시 발급 불가 (Single Point of Failure)
- Redis ↔ DB 간 정합성 검증 필요 (Spring Batch Job으로 해결)
- 재고 초기화 시 Redis에 별도 SET 필요 (Admin API 구현)

### 보완 조치
- Spring Batch `CouponSettlementJob`으로 Redis ↔ DB 정합성 주기 검증
- Kafka Producer 실패 시 Redis 재고 복원 콜백 구현
- Admin API로 Redis 재고 초기화 제공
