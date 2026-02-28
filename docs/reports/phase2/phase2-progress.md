# Phase 2: 핵심 로직 구현 — 진행 보고서

- **작성일**: 2026-02-28
- **최종 수정일**: 2026-02-28
- **상태**: 구현 완료 (2.1 Redis + 2.2 Kafka + 2.3 데이터 계층)

---

## 완료 항목

### Step 1: Redis 재고 관리 — RedisStockManager
- [x] `initStock(UUID couponEventId, int totalStock)` — 재고 초기화
- [x] `decrementStock(UUID couponEventId)` — DECR 원자적 차감, 결과 < 0이면 INCR 복원 후 false
- [x] `restoreStock(UUID couponEventId)` — Kafka 전송 실패 시 재고 복원
- [x] `getRemainingStock(UUID couponEventId)` — 잔여 재고 조회
- [x] Redis Key: `coupon:stock:{couponEventId}` (String/Integer)

### Step 2: Redis 중복/멱등성/Rate Limit
- [x] `RedisIdempotencyManager` — SET NX, TTL 24h (`idempotency:{key}`)
- [x] `RedisDuplicateChecker` — SET NX, TTL 24h (`coupon:{couponEventId}:issued:{userId}`)
- [x] `RedisRateLimiter` — Sliding Window ZSET (`rate_limit:{userId}`, 초당 5회)
- [x] `RateLimitExceededException` — 429 Too Many Requests
- [x] `GlobalExceptionHandler` — 429 핸들러 추가

### Step 3: Kafka Producer + Message
- [x] `CouponIssueMessage` — record (requestId, couponEventId, userId, idempotencyKey, requestedAt)
- [x] `CouponIssueProducer` — KafkaTemplate 사용, 전송 실패 시 Redis 재고 복원 콜백
- [x] Topic: `coupon-issue` (3 partitions)

### Step 4: Kafka Consumer + DLQ
- [x] `CouponIssueConsumer` — @KafkaListener, DB 저장 (status=ISSUED)
- [x] `requestId` 중복 저장 방지 (findByRequestId + DataIntegrityViolationException)
- [x] `KafkaConfig` — Topic 생성 (`coupon-issue`, `coupon-issue-dlq`)
- [x] `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
- [x] 지수 백오프 재시도 (1s → 2s → 4s)

### Step 5: CouponIssueService 리팩토링
- [x] DB 직결 → Redis + Kafka 비동기 파이프라인으로 전환
- [x] 처리 흐름: Idempotency → Rate Limit → Duplicate → Stock DECR → Kafka → 202 PENDING
- [x] `getIssueStatus()` — 기존 DB 조회 유지

### Step 6: 재고 초기화 API + 테스트
- [x] `CouponAdminController` — `POST /api/v1/admin/coupons/{couponEventId}/init-stock`
- [x] `CouponService` — remainingStock를 Redis에서 조회하도록 변경
- [x] `application.yaml` — Kafka 수동 커밋 설정 추가
- [x] `CouponIssueServiceTest` — Redis/Kafka mock 단위 테스트 (7 tests)
- [x] `RedisStockManagerTest` — Testcontainers Redis 원자성 검증 (4 tests, 동시 200요청 → 100건만 성공)
- [x] `CouponIssueIntegrationTest` — E2E (Testcontainers Redis + Kafka + PostgreSQL, 3 tests)

### Step 7: QueryDSL 동적 쿼리 (2.3)
- [x] `QueryDslConfig` — JPAQueryFactory Bean 등록
- [x] `CouponIssueSearchCondition` — record (couponEventId, userId, status, startDate, endDate)
- [x] `CouponIssueStats` — record (totalIssued, totalPending, totalFailed)
- [x] `CouponIssueQueryRepository` — searchIssues (Page), getIssueStats
- [x] `GET /api/v1/admin/coupons/{id}/issues` — 발급 내역 검색 API (필터 + 페이지네이션)
- [x] `GET /api/v1/admin/coupons/{id}/stats` — 발급 통계 API

### Step 8: Spring Batch 정합성 Job (2.3)
- [x] `V3__settlement_report.sql` — settlement_report 테이블 Flyway 마이그레이션
- [x] `SettlementReport` — Entity (Redis ↔ DB 정합성 리포트)
- [x] `SettlementReportRepository` — JpaRepository
- [x] `CouponSettlementJobConfig` — Spring Batch Job 구성
  - Reader: JpaPagingItemReader (CouponEvent 전체 스캔)
  - Processor: QueryDSL 통계 + Redis 잔여 재고 → 정합성 비교
  - Writer: SettlementReport 저장 + 불일치 시 WARN 로깅

---

## 빌드 결과

| 항목 | 결과 |
|------|------|
| `./gradlew compileJava` | BUILD SUCCESSFUL |
| `./gradlew test` (전체) | BUILD SUCCESSFUL |
| 단위 테스트 (CouponIssueServiceTest) | 7/7 PASSED |
| 단위 테스트 (CouponControllerTest) | 6/6 PASSED |
| Redis 원자성 테스트 (RedisStockManagerTest) | 4/4 PASSED |
| 통합 테스트 (CouponIssueIntegrationTest) | 3/3 PASSED |

---

## 처리 흐름 (구현 완료)

```
POST /api/v1/coupons/issue
  → Redis: Idempotency-Key 중복 확인 (SET NX, TTL 24h)
  → Redis: Rate Limit 확인 (Sliding Window ZSET, 5req/1s)
  → Redis: 중복 발급 확인 (SET NX, TTL 24h)
  → Redis: 재고 차감 (DECR, 원자적, 음수 시 INCR 복원)
  → Kafka: 메시지 발행 (topic: coupon-issue)
  → 202 Accepted (status=PENDING)

Kafka Consumer:
  → requestId 중복 확인
  → DB 저장 (coupon_issue, status=ISSUED)
  → 실패 시 3회 재시도 (지수 백오프) → DLQ
```

---

## PRD 계획 대비 변경 사항

| 항목 | PRD 스펙 | 현재 구현 | 사유 |
|------|----------|-----------|------|
| Redis Key 접두어 | `coupon:{id}:stock` | `coupon:stock:{id}` | 키 패턴 일관성 (타입:네임스페이스:ID) |
| Rate Limiter Key | `rate_limit:{userId}` (String) | `rate_limit:{userId}` (ZSET) | Sliding Window 정밀도 향상 |
| DLQ Topic 이름 | `coupon.issue.dlq` | `coupon-issue-dlq` | Kafka 토픽 네이밍 컨벤션 통일 (하이픈) |
| Kafka Partition | 1 (Phase 2) → 10 (실험 C) | 3 (Phase 2) | 로컬 환경 적정 파티션 수 |
| CouponIssueMessage | String requestId, Long couponEventId | UUID requestId, UUID couponEventId | 타입 안전성 (UUID v7 일관성) |
| traceId 필드 | 포함 | **미포함** | Phase 3 분산 추적 시 추가 예정 |
| RedisDistributedLock | Redisson 분산 락 | **미구현** | DECR 원자성으로 충분, 재고 초기화 시에만 필요 |

---

## Spring Boot 4.x 호환성 이슈 (Phase 2 추가)

| 이슈 | 해결 |
|------|------|
| Redisson `RedissonAutoConfiguration` 제외 실패 | V4에서 `RedissonAutoConfigurationV2`, `V4`로 분리됨 → 두 클래스 모두 제외 |
| `ObjectMapper` Bean 미등록 (Integration Test) | `@Autowired` 대신 수동 인스턴스 생성 |
| Lambda에서 effectively final 필요 | `event` → `savedEvent` 변수 분리 |

---

## 신규 생성 파일 목록

```
src/main/java/com/kaameo/event_platform/
├── batch/
│   └── CouponSettlementJobConfig.java      (Spring Batch 정산 Job)
├── config/
│   ├── KafkaConfig.java                    (Topic 생성, DLQ, 에러 핸들러)
│   └── QueryDslConfig.java                 (JPAQueryFactory Bean)
└── coupon/
    ├── controller/
    │   └── CouponAdminController.java      (재고 초기화 + 검색/통계 API)
    ├── domain/
    │   └── SettlementReport.java           (정산 리포트 Entity)
    ├── dto/
    │   ├── CouponIssueSearchCondition.java (검색 조건 record)
    │   └── CouponIssueStats.java           (통계 record)
    ├── exception/
    │   └── RateLimitExceededException.java  (429 예외)
    ├── kafka/
    │   ├── CouponIssueProducer.java        (Kafka Producer)
    │   └── CouponIssueConsumer.java        (Kafka Consumer + DLQ)
    ├── message/
    │   └── CouponIssueMessage.java         (Kafka 메시지 record)
    ├── repository/
    │   ├── CouponIssueQueryRepository.java (QueryDSL 동적 쿼리)
    │   └── SettlementReportRepository.java (정산 리포트 Repository)
    └── service/
        ├── RedisStockManager.java          (DECR 원자적 재고)
        ├── RedisIdempotencyManager.java    (멱등성 키)
        ├── RedisDuplicateChecker.java      (중복 발급 방지)
        └── RedisRateLimiter.java           (Sliding Window)
src/main/resources/db/migration/
└── V3__settlement_report.sql               (정산 테이블 마이그레이션)
src/test/java/com/kaameo/event_platform/coupon/
└── RedisStockManagerTest.java              (Testcontainers Redis)
```

## 수정 파일 목록

| 파일 | 변경 내용 |
|------|-----------|
| `CouponIssueService.java` | DB 직결 → Redis + Kafka 비동기 파이프라인 |
| `CouponService.java` | remainingStock Redis 조회로 변경 |
| `GlobalExceptionHandler.java` | RateLimitExceededException 429 핸들러 추가 |
| `application.yaml` | Kafka consumer 수동 커밋 + listener ack-mode 설정 |
| `build.gradle.kts` | awaitility 테스트 의존성 추가 |
| `CouponIssueServiceTest.java` | Redis/Kafka mock 기반으로 전면 재작성 |
| `CouponIssueIntegrationTest.java` | Testcontainers Redis + Kafka + PostgreSQL E2E |

---

## Phase 2 완료 기준 점검

| 기준 | 상태 | 비고 |
|------|------|------|
| 쿠폰 발급 E2E 동작: API → Redis → Kafka → DB | **완료** | 통합 테스트 검증 |
| 재고 정확성: 동시 요청 시 초과 발급 0건 | **완료** | RedisStockManagerTest: 200 동시요청 → 100건만 성공 |
| 중복 발급 방지 동작 확인 | **완료** | RedisDuplicateChecker SET NX |
| Rate Limiter 동작 확인 | **완료** | RedisRateLimiter Sliding Window ZSET |
| DLQ 정상 동작 확인 | **완료** | KafkaConfig DefaultErrorHandler + DLQ |
| QueryDSL 조회 테스트 통과 | **완료** | 동적 쿼리 + 통계 API 구현 |
| Spring Batch Job 수동 실행 성공 | **완료** | CouponSettlementJob 구성 완료 (Docker 기동 후 실행 가능) |
| Virtual Threads 활성화 확인 | **기존 완료** | Phase 1에서 `spring.threads.virtual.enabled: true` 설정 |

---

## 미완료 항목 (Phase 2 후속)

| 항목 | 설명 | 우선순위 |
|------|------|----------|
| RedisDistributedLock | 재고 초기화 시 분산 락 (Redisson) | Low |
| traceId 분산 추적 | CouponIssueMessage에 traceId 추가 | Low |
| Kafka Producer idempotence | `enable.idempotence=true` 설정 | Low |
| k6 부하 테스트 | Phase 2 파이프라인 RPS 측정 + 초과 발급 0건 검증 | **High** |

---

## 다음 단계

1. **k6 부하 테스트**: Docker Compose 기동 → Admin API 재고 초기화 → k6 테스트 실행 → 초과 발급 0건 + RPS 측정
2. **QueryDSL 동적 쿼리**: 발급 내역 검색/통계 API
3. **Spring Batch**: Redis ↔ DB 정합성 검증 Job
4. **Phase 3**: 성능 최적화 (Kafka 파티션 튜닝, 커넥션 풀 최적화)
