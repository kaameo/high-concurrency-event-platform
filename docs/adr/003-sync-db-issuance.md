# ADR-003: Phase 1에서 동기 DB 발급 방식 채택

- **Status**: Accepted (Phase 2에서 Superseded 예정)
- **Date**: 2026-02-28
- **Deciders**: 개발팀

## Context

선착순 쿠폰 발급 시스템에서 발급 요청을 어떻게 처리할 것인가. 최종 목표는 Redis 재고 차감 + Kafka 비동기 처리이지만, Phase 1에서는 기본 API와 비즈니스 로직의 정확성을 먼저 검증해야 한다.

### 최종 아키텍처 (Phase 2 목표)
```
Client → API → Redis DECR (재고) → Kafka Produce → Consumer → DB 저장
```

### Phase 1 질문
Phase 2 아키텍처를 한 번에 구현할 것인가, 아니면 단순한 구현부터 시작할 것인가?

## Decision Drivers

- 비즈니스 로직 정확성 우선 검증
- API 인터페이스 안정화 (Phase 2에서 내부 구현만 교체)
- 인프라 의존성 최소화 (Phase 1에서는 PostgreSQL만으로 동작)
- 테스트 용이성

## Considered Options

### Option 1: Phase 2 아키텍처 한 번에 구현
- Redis + Kafka + DB 전체 파이프라인
- 복잡도 높음, 디버깅 어려움
- 인프라 전체 의존

### Option 2: 동기 DB 직접 발급 ✅ 선택
- PostgreSQL 트랜잭션 내에서 재고 체크 + 발급 + 저장
- 단순하고 테스트 용이
- API 인터페이스는 최종 형태와 동일 (`202 Accepted`)

## Decision

**Phase 1에서는 DB 직접 동기 발급 방식으로 구현한다.**

### 처리 흐름
```
POST /api/v1/coupons/issue
  → Idempotency-Key 중복 확인 (DB)
  → CouponEvent 조회 + isActive 검증
  → 사용자 중복 발급 확인 (DB)
  → 발급 수량 체크 (COUNT vs totalStock)
  → CouponIssue 저장 (DB)
  → 202 Accepted 응답
```

### Phase 2 전환 계획
API 인터페이스(request/response)는 변경 없이 내부 구현만 교체:

| 단계 | Phase 1 (현재) | Phase 2 (예정) |
|------|---------------|---------------|
| 재고 확인 | `COUNT` 쿼리 | Redis `DECR` (O(1) 원자적) |
| 발급 처리 | DB 직접 저장 | Kafka Produce → Consumer 저장 |
| 중복 확인 | DB 쿼리 | Redis `SISMEMBER` |
| 응답 상태 | ISSUED (즉시) | PENDING → ISSUED (비동기) |

### 알려진 제약사항

1. **Race Condition**: `count → check → save` 패턴은 동시성 안전하지 않음
   - 임시 방어: `SELECT FOR UPDATE` 적용 가능
   - 근본 해결: Phase 2 Redis DECR
2. **성능 한계**: DB 트랜잭션 직렬화로 높은 TPS 불가
3. **응답**: 동기 처리이므로 실제로는 ISSUED 즉시 반환 (202의 의미와 불일치하지만 Phase 2 전환 대비)

## Consequences

### Positive
- 비즈니스 로직 정확성 검증 가능 (멱등성, 중복 방지, 재고 관리)
- API 인터페이스 안정화 → Phase 2에서 내부만 교체
- PostgreSQL만으로 동작 → 개발/테스트 환경 단순
- 테스트 코드 재활용 가능

### Negative
- 동시성 Race Condition 존재 (초과 발급 가능)
- 높은 TPS 처리 불가 (DB 병목)
- Phase 2 전환 시 Service 계층 재구현 필요

### Risks
- Phase 1 코드가 Phase 2로 전환되지 않고 방치될 위험 → Phase 2 계획 문서로 방지

## References

- [docs/plan/phase2-core.md](../plan/phase2-core.md) — Phase 2 Redis + Kafka 구현 계획
- [phase1-standards-review.md](../reports/phase1-standards-review.md) — Race Condition 분석 (P0 #1)
