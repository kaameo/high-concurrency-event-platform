# ADR-007: Kafka 비동기 방식 성능 우위 실증 확인

- **Status**: Accepted
- **Date**: 2026-02-28
- **Deciders**: 개발팀
- **Validates**: [ADR-006](./006-kafka-async-pipeline.md) (Kafka 비동기 파이프라인 채택)

## Context

ADR-006에서 Kafka 비동기 파이프라인을 이론적 근거로 채택했으나, 실제 성능 차이를 정량적으로 검증하지 않았음. Phase 3 실험 A에서 동일 조건(500 VU, 100,000장 재고)으로 DB 직결 동기 방식과 Kafka 비동기 방식을 비교 측정.

## Decision Drivers

- ADR-006 의사결정의 실증적 검증 필요
- PRD KPI 달성 가능성 판단 (100k TPS, p95 ≤ 200ms)
- 아키텍처 방향성 확정

## Experiment Design

### A-1: Kafka 비동기
```
Request → Redis DECR → Kafka Produce (acks=all) → 202 Accepted
Consumer → DB INSERT (비동기)
```

### A-2: DB 직결 동기
```
Request → Redis DECR → DB INSERT (@Transactional) → 200 OK
```

두 방식 모두 동일한 Redis 재고 차감 + 멱등성 + 중복 체크 + Rate Limit 사용.

## Measured Results (500 VU, 2m30s)

| 항목 | Async (Kafka) | Sync (DB) | 비율 |
|------|--------------|-----------|------|
| RPS | 4,711/s | 3,019/s | 1.56x |
| p50 Latency | 12.09ms | 66.75ms | 5.5x |
| p95 Latency | 46.98ms | 96.19ms | 2.0x |
| Max Latency | 86.25ms | 359.27ms | 4.2x |
| 성공률 | 100% | 100% | 동일 |
| 재고 정합성 | 100,000건 정확 | 100,000건 정확 | 동일 |

## Decision

**ADR-006의 Kafka 비동기 방식이 실증적으로 우수함을 확인.** 향후 확장(파티션 튜닝, Consumer 스케일링)도 Kafka 기반으로 진행.

## Rationale

### 처리량
- 동일 VU에서 Async가 1.56배 높은 RPS
- Async는 Kafka produce 후 즉시 반환 → 스레드 빠르게 해제
- Sync는 DB INSERT 완료까지 스레드 점유

### 응답 시간
- Async가 전 구간 우수 (p50 5.5배, p95 2배, max 4.2배)
- Sync의 latency 원인: HikariCP 풀(기본 10개) 경합 → 커넥션 대기
- Async는 API 계층에서 DB 커넥션을 사용하지 않으므로 경합 없음

### 안정성
- Async max latency 86ms — 편차 작고 예측 가능
- Sync max latency 359ms — 피크 시 스파이크 (4배 이상)

### 정합성
- 두 방식 모두 Redis DECR 원자성에 의존 → 초과 발급 0건
- 방식과 무관하게 재고 정합성 보장

## Consequences

### 확정 사항
- Kafka 비동기 파이프라인을 production 아키텍처로 확정
- DB 직결 방식(`/issue-sync`)은 실험용으로만 유지, production 미사용
- 파티션 확장(실험 C)으로 추가 처리량 확보 예정

### 향후 과제
- HikariCP 풀 사이즈 튜닝 시 Sync 성능 개선 여지 존재 (미검증)
- Kafka acks 설정 완화(acks=1) 시 Async latency 추가 개선 가능 (신뢰성 트레이드오프)

## References

- [실험 A 상세 리포트](../reports/phase3/experiment-a-report.md)
- [Phase 3 트러블슈팅](../reports/phase3/phase3-troubleshooting.md)
