# Implementation Plan Overview

## Phase 구성

| Phase | 문서 | 기간 | 핵심 목표 |
|-------|------|------|-----------|
| 1 | [phase1-foundation.md](./phase1-foundation.md) | 1~2주 | 프로젝트 초기화, 인프라, 기본 API |
| 2 | [phase2-core.md](./phase2-core.md) | 3~4주 | Redis 재고, Kafka 비동기, 데이터 계층 |
| 3 | [phase3-experiments.md](./phase3-experiments.md) | 2~3주 | 성능 실험 A/B/C, 부하 테스트 |
| 4 | [phase4-resilience.md](./phase4-resilience.md) | 1~2주 | 노드 장애 복구, 회복 탄력성 |
| 5 | [phase5-finalize.md](./phase5-finalize.md) | 1주 | CI/CD, 문서화, 산출물 |

## 기술적 의사결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| Language | Java 21 | Virtual Threads로 대규모 동시 처리 |
| Framework | Spring Boot 4.0.3 | 현재 표준 |
| Build | Gradle (Kotlin DSL) | IDE 자동완성, Spring Boot 기본값 |
| DB | PostgreSQL | 표준적, JSONB 확장성 |
| Cache/Lock | Redis + Redisson | Atomic Counter + 분산 락 |
| Messaging | Kafka | 요청 버퍼링, Write-Behind 패턴 |
| Query | QueryDSL | 동적 쿼리 (초기 포함, 추후 개선) |
| Batch | Spring Batch | 정산/집계 (초기 포함, 추후 개선) |
| K8s 로컬 | kind (Master 1 + Worker 2) | 가성비 + HA 테스트 가능 |
| 부하 도구 | k6 (메인) + Locust (보조) | 비교 실험 |
| Monitoring | Prometheus + Grafana | Phase 1부터 구성 |
| API Docs | Springdoc-openapi (Swagger) | |
| CI/CD | GitHub Actions | 보류 — 핵심 기능 완료 후 도입 |

## 패키지 구조

```
src/main/java/com/kaameo/event_platform/
├── config/                  # 설정 클래스
│   ├── RedisConfig.java
│   ├── KafkaProducerConfig.java
│   ├── KafkaConsumerConfig.java
│   ├── RedissonConfig.java
│   ├── SwaggerConfig.java
│   └── BatchConfig.java
├── coupon/                  # 쿠폰 도메인 (Bounded Context)
│   ├── controller/
│   │   └── CouponController.java
│   ├── service/
│   │   ├── CouponService.java
│   │   ├── CouponIssueService.java
│   │   └── StockService.java
│   ├── domain/
│   │   ├── Coupon.java          # Entity
│   │   ├── CouponIssue.java     # Entity
│   │   ├── CouponStatus.java    # Enum
│   │   └── IssueStatus.java     # Enum
│   ├── repository/
│   │   ├── CouponRepository.java
│   │   ├── CouponIssueRepository.java
│   │   └── CouponIssueQueryRepository.java  # QueryDSL
│   ├── dto/
│   │   ├── CouponIssueRequest.java
│   │   ├── CouponIssueResponse.java
│   │   └── CouponDetailResponse.java
│   └── exception/
│       ├── CouponNotFoundException.java
│       ├── CouponSoldOutException.java
│       └── DuplicateIssueException.java
├── kafka/                   # Kafka 관련
│   ├── producer/
│   │   └── CouponIssueProducer.java
│   ├── consumer/
│   │   └── CouponIssueConsumer.java
│   └── message/
│       └── CouponIssueMessage.java
├── redis/                   # Redis 관련
│   ├── RedisStockManager.java       # DECR 기반 재고
│   ├── RedisRateLimiter.java        # Rate Limiting
│   └── RedisDistributedLock.java    # Redisson 분산 락
├── batch/                   # Spring Batch
│   └── CouponSettlementJob.java
├── common/                  # 공통
│   ├── dto/
│   │   └── ApiResponse.java
│   ├── exception/
│   │   └── GlobalExceptionHandler.java
│   └── util/
│       └── TimeUtils.java
└── EventPlatformApplication.java
```

```
src/test/java/com/kaameo/event_platform/
├── coupon/
│   ├── controller/
│   │   └── CouponControllerTest.java
│   ├── service/
│   │   ├── CouponIssueServiceTest.java
│   │   └── StockServiceTest.java
│   └── repository/
│       └── CouponIssueQueryRepositoryTest.java
├── kafka/
│   ├── CouponIssueProducerTest.java
│   └── CouponIssueConsumerTest.java
├── redis/
│   ├── RedisStockManagerTest.java
│   └── RedisRateLimiterTest.java
└── integration/
    ├── CouponIssueIntegrationTest.java
    └── ConcurrencyIssueTest.java
```
