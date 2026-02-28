# ADR-006: Kafka 비동기 발급 파이프라인 채택

- **Status**: Accepted
- **Date**: 2026-02-28
- **Deciders**: 개발팀
- **Supersedes**: [ADR-003](./003-sync-db-issuance.md) (Phase 1 동기 DB 발급)

## Context

Phase 1에서 쿠폰 발급 시 DB에 동기적으로 INSERT하는 방식이 병목. 재고 차감(Redis DECR)과 DB 저장을 분리하여 응답 레이턴시를 줄이고 처리량을 높여야 함.

## Decision Drivers

- API 응답 시간 최소화 (사용자 경험)
- DB 쓰기 부하를 비동기로 분산
- 메시지 유실 없는 신뢰성 보장
- 실패 시 재처리 가능한 구조

## Considered Options

### Option 1: Kafka 비동기 파이프라인
```
API → Redis 차감 → Kafka Produce → 202 Accepted
Consumer → DB INSERT (비동기)
```

### Option 2: Spring @Async
```
API → Redis 차감 → @Async DB INSERT → 202 Accepted
```

### Option 3: 동기 Redis + DB
```
API → Redis 차감 → DB INSERT → 200 OK
```

## Decision

**Option 1: Kafka 비동기 파이프라인** 채택.

## Rationale

| 기준 | Kafka | @Async | 동기 Redis+DB |
|------|-------|--------|--------------|
| 메시지 영속성 | O (디스크) | X (메모리) | N/A |
| 서버 재시작 시 유실 | 없음 | **있음** | 없음 |
| 재처리 | DLQ + 오프셋 | 수동 | N/A |
| DB 부하 분산 | O (Consumer 속도 조절) | 부분적 | X |
| 모니터링 | Kafka UI + 메트릭 | 제한적 | N/A |
| 확장성 | 파티션 + Consumer Group | 서버 인스턴스 제한 | DB 병목 |

- `@Async`는 서버 재시작 시 진행 중인 작업 유실 위험
- Kafka는 메시지를 디스크에 영속화하므로 유실 없음
- DLQ로 실패 메시지 자동 격리 → 수동 재처리 가능
- 파티션 수 조절로 Consumer 병렬 처리 확장 가능

## 구현 세부사항

### Topic 설계
| Topic | Partitions | 용도 |
|-------|-----------|------|
| `coupon-issue` | 3 | 발급 요청 메시지 |
| `coupon-issue-dlq` | 1 | 실패 메시지 |

### 에러 핸들링
- `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
- 재시도: 3회, 지수 백오프 (1s → 2s → 4s)
- Consumer 수동 커밋 (`enable.auto.commit=false`, `ack-mode=record`)

### 보상 처리
- Kafka Producer 전송 실패 시 Redis 재고 복원 (INCR)
- DB `requestId` UNIQUE 제약으로 Consumer 중복 처리 방지

## Consequences

### 긍정적
- API 응답에서 DB 쓰기 제거 → 레이턴시 감소
- DB 장애가 API 응답에 영향 없음 (Kafka 버퍼링)
- Consumer 독립 확장 가능

### 부정적
- 최종 일관성 (Eventual Consistency) — 발급 직후 DB 조회 시 미반영 가능
- Kafka 인프라 운영 복잡도 증가
- 상태 조회 시 `PENDING` → `ISSUED` 전이 딜레이

### 보완 조치
- `GET /api/v1/coupons/requests/{requestId}` — 상태 폴링 API 제공
- Spring Batch 정합성 Job으로 Redis ↔ DB 주기 검증
- Kafka UI (`localhost:9091`)로 토픽/메시지 모니터링
