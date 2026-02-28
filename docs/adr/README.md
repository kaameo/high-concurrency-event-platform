# Architecture Decision Records (ADR)

프로젝트의 주요 아키텍처 결정을 기록합니다.

## 형식

[MADR (Markdown Any Decision Records)](https://adr.github.io/madr/) 형식을 따릅니다.

## 목록

| # | 제목 | 상태 | 날짜 |
|---|------|------|------|
| [ADR-001](./001-uuid-v7-primary-key.md) | UUID v7을 Primary Key 전략으로 채택 | Accepted | 2026-02-28 |
| [ADR-002](./002-spring-boot-4.md) | Spring Boot 4.x 채택 및 마이그레이션 전략 | Accepted | 2026-02-28 |
| [ADR-003](./003-sync-db-issuance.md) | Phase 1 동기 DB 발급 방식 채택 | Superseded by ADR-005, ADR-006 | 2026-02-28 |
| [ADR-004](./004-idempotency-key.md) | Idempotency-Key 기반 중복 방지 | Accepted | 2026-02-28 |
| [ADR-005](./005-redis-atomic-stock.md) | Redis DECR 원자적 재고 차감 채택 | Accepted | 2026-02-28 |
| [ADR-006](./006-kafka-async-pipeline.md) | Kafka 비동기 발급 파이프라인 채택 | Accepted | 2026-02-28 |
