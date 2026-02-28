# PLAN: 선착순 이벤트 플랫폼 구현 계획

상세 구현 계획은 `plan/` 폴더에 Phase별로 분리되어 있습니다.

> **[plan/README.md](./plan/README.md)** — 전체 개요, 기술 의사결정, 패키지 구조

| Phase | 문서 | 기간 | 핵심 목표 |
|-------|------|------|-----------|
| 1 | [phase1-foundation.md](./plan/phase1-foundation.md) | 1~2주 | 프로젝트 초기화, 인프라, 기본 API |
| 2 | [phase2-core.md](./plan/phase2-core.md) | 3~4주 | Redis 재고, Kafka 비동기, 데이터 계층 |
| 3 | [phase3-experiments.md](./plan/phase3-experiments.md) | 2~3주 | 성능 실험 A/B/C, 100k TPS 포함 PRD KPI(성공률/지연/정합성) 검증 |
| 4 | [phase4-resilience.md](./plan/phase4-resilience.md) | 1~2주 | 노드 장애 복구, 데이터 무손실 검증 |
| 5 | [phase5-finalize.md](./plan/phase5-finalize.md) | 1주 | CI/CD, 문서화, 산출물 표준화 |
