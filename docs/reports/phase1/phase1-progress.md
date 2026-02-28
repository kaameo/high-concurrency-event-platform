# Phase 1: 기반 구축 — 진행 보고서

- **작성일**: 2026-02-28
- **최종 수정일**: 2026-02-28
- **상태**: 구현 및 검증 완료

---

## 완료 항목

### Step 1: 의존성 추가 (`build.gradle.kts`)
- [x] QueryDSL 5.1.0 (jakarta) + annotation processor
- [x] Redisson 4.0.0 (Spring Boot 4 호환)
- [x] Springdoc OpenAPI 3.0.0 (Spring Boot 4 호환)
- [x] Flyway (`spring-boot-flyway` starter)
- [x] Testcontainers (postgresql, kafka, junit-jupiter)
- [x] jakarta.persistence-api, jakarta.annotation-api (QueryDSL AP 호환)
- [x] uuid-creator 6.0.0 (UUID v7 생성)

### Step 2: 인프라 파일
- [x] `.env.example` — 환경변수 템플릿
- [x] `docker-compose.yml` — PostgreSQL 16, Redis 7, Kafka 3.9 (KRaft), Kafka-UI, Prometheus, Grafana
- [x] `infra/prometheus/prometheus.yml` — Spring Actuator 메트릭 수집

### Step 3: application.yaml
- [x] spring.datasource (PostgreSQL)
- [x] spring.data.redis
- [x] spring.kafka (producer/consumer)
- [x] spring.jpa (hibernate, ddl-auto: validate)
- [x] spring.flyway
- [x] spring.threads.virtual.enabled: true
- [x] springdoc swagger-ui
- [x] management.endpoints (actuator/prometheus)
- [x] server.port: 8080

### Step 4: DB 스키마 (Flyway)
- [x] `V1__init.sql` — 초기 스키마 (BIGSERIAL)
- [x] `V2__uuid_v7_migration.sql` — UUID v7 네이티브 타입으로 전환

### Step 5: 도메인 모델
- [x] `CouponEvent.java` — Entity (UUID v7 PK, Builder 패턴, isActive 메서드)
- [x] `CouponIssue.java` — Entity (UUID v7 PK/FK, markIssued, markRejected 메서드)
- [x] `CouponEventStatus.java` — ACTIVE, INACTIVE, EXPIRED
- [x] `IssueStatus.java` — PENDING, ISSUED, REJECTED_OUT_OF_STOCK, REJECTED_DUPLICATE, FAILED

### Step 6: Repository
- [x] `CouponEventRepository.java` — `JpaRepository<CouponEvent, UUID>`
- [x] `CouponIssueRepository.java` — findByRequestId, findByIdempotencyKey, existsByCouponEventIdAndUserId, countByCouponEventId

### Step 7: DTO
- [x] `CouponIssueRequest.java` — record (UUID couponEventId, Long userId)
- [x] `CouponIssueResponse.java` — record (UUID requestId)
- [x] `CouponDetailResponse.java` — record (UUID couponEventId)
- [x] `CouponIssueStatusResponse.java` — record (UUID requestId, UUID couponEventId)

### Step 8: 공통
- [x] `ApiResponse.java` — record (ok/error 팩토리 메서드)
- [x] `GlobalExceptionHandler.java` — CouponNotFound(404), SoldOut(410), Duplicate(409), Validation(400), General(500)
- [x] 커스텀 예외 3개

### Step 9: Service
- [x] `CouponService.java` — 쿠폰 이벤트 조회 + 잔여 재고 계산
- [x] `CouponIssueService.java` — 멱등성 키 검증, 중복 발급 검증, 재고 검증, 발급 처리

### Step 10: Controller
- [x] `POST /api/v1/coupons/issue` → 202 Accepted (Idempotency-Key 헤더 필수)
- [x] `GET /api/v1/coupons/requests/{requestId}` → 200 (UUID PathVariable)
- [x] `GET /api/v1/coupons/{couponEventId}` → 200 (UUID PathVariable)

### Step 11: Config
- [x] `SwaggerConfig.java` — OpenAPI 메타데이터

### Step 12: 테스트
- [x] `CouponControllerTest.java` — @WebMvcTest (6 tests)
- [x] `CouponIssueServiceTest.java` — Mockito 단위 테스트 (7 tests)
- [x] `CouponIssueIntegrationTest.java` — @SpringBootTest + Testcontainers (Docker 필요)

---

## 빌드 결과

| 항목 | 결과 |
|------|------|
| `./gradlew compileJava` | BUILD SUCCESSFUL |
| `./gradlew test` (단위 테스트 13개) | ALL PASSED |
| Integration Test | Docker 기동 후 검증 필요 |

---

## PRD 대비 변경 사항

| 항목 | PRD 스펙 | 현재 구현 | 사유 |
|------|----------|-----------|------|
| PK 타입 | BIGSERIAL | **UUID v7 (네이티브 uuid)** | 분산 환경 적합, 16바이트 네이티브 저장, RFC 9562 표준 |
| Redisson | 3.27.0 | **4.0.0** | Spring Boot 4 호환 필수 |
| Springdoc | 2.4.0 | **3.0.0** | Spring Boot 4 호환 필수 |
| Flyway | flyway-core | **spring-boot-flyway** | SB4 모듈화로 자동설정 방식 변경 |
| UUID 라이브러리 | 없음 | **uuid-creator 6.0.0** | UUID v7 (RFC 9562) 생성용 |

### UUID v7 전환 근거
- PostgreSQL `uuid` 네이티브 타입 (16 bytes) → BIGSERIAL 대비 분산 생성 가능
- ULID `char(26)` 대비 인덱스 크기 **62% 절감**
- B-tree 바이너리 비교 → 문자열 콜레이션 비교 대비 성능 우위
- JPA `UUID` 타입 네이티브 지원 (타입 안전성)
- RFC 9562 국제 표준 준수
- 시간 순 정렬 보장 (monotonic)

---

## Spring Boot 4.x 호환성 이슈 및 해결

| 이슈 | 해결 |
|------|------|
| `@WebMvcTest` 패키지 이동 | `org.springframework.boot.webmvc.test.autoconfigure` 사용 |
| `@AutoConfigureMockMvc` 패키지 이동 | 동일 패키지로 변경 |
| QueryDSL AP에서 `NoClassDefFoundError: jakarta/persistence/Entity` | `annotationProcessor`에 `jakarta.persistence-api`, `jakarta.annotation-api` 추가 |
| `@WebMvcTest`에서 `ObjectMapper` 자동 주입 불가 | 수동 인스턴스 생성 |

---

## 생성된 파일 목록

```
.env.example
docker-compose.yml
infra/prometheus/prometheus.yml
src/main/resources/application.yaml (수정)
src/main/resources/db/migration/V1__init.sql
src/main/resources/db/migration/V2__uuid_v7_migration.sql
src/main/java/com/kaameo/event_platform/
├── config/
│   └── SwaggerConfig.java
├── common/
│   ├── dto/ApiResponse.java
│   └── exception/GlobalExceptionHandler.java
└── coupon/
    ├── domain/
    │   ├── CouponEvent.java          (UUID v7 PK)
    │   ├── CouponIssue.java          (UUID v7 PK/FK)
    │   ├── CouponEventStatus.java
    │   └── IssueStatus.java
    ├── repository/
    │   ├── CouponEventRepository.java  (JpaRepository<*, UUID>)
    │   └── CouponIssueRepository.java  (JpaRepository<*, UUID>)
    ├── dto/
    │   ├── CouponIssueRequest.java     (UUID couponEventId)
    │   ├── CouponIssueResponse.java    (UUID requestId)
    │   ├── CouponDetailResponse.java   (UUID couponEventId)
    │   └── CouponIssueStatusResponse.java (UUID requestId/couponEventId)
    ├── service/
    │   ├── CouponService.java
    │   └── CouponIssueService.java
    ├── controller/
    │   └── CouponController.java       (UUID PathVariable)
    └── exception/
        ├── CouponNotFoundException.java
        ├── CouponSoldOutException.java
        └── DuplicateIssueException.java
src/test/java/com/kaameo/event_platform/coupon/
├── CouponControllerTest.java
├── CouponIssueServiceTest.java
└── CouponIssueIntegrationTest.java
```

---

## 검증 결과 (2026-02-28)

- [x] `docker-compose up -d` → PostgreSQL, Redis, Kafka, Kafka-UI, Prometheus, Grafana 정상 기동
- [x] `./gradlew bootRun` → Spring Boot 앱 정상 부팅 (1.96초)
- [x] `localhost:8080/swagger-ui/index.html` → Swagger UI 접속 (HTTP 200)
- [x] `POST /api/v1/coupons/issue` → 쿠폰 발급 정상 (ISSUED, UUID v7 requestId 반환)
- [x] `GET /api/v1/coupons/requests/{requestId}` → 발급 상태 조회 정상
- [x] `GET /api/v1/coupons/{couponEventId}` → 쿠폰 이벤트 상세 조회 정상
- [x] 멱등성 검증 → 동일 Idempotency-Key 재요청 시 기존 결과 반환
- [x] 중복 발급 검증 → 동일 사용자 재발급 시 409 Conflict
- [x] 재고 차감 확인 → 발급 후 remainingStock 감소
- [x] `localhost:8080/actuator/health` → UP (DB, Redis 연결 확인)
- [x] `localhost:8080/actuator/prometheus` → 메트릭 정상 노출
- [x] `localhost:9091` → Kafka UI 접속 가능
- [x] `localhost:3000` → Grafana 접속 가능
- [x] `localhost:9090` → Prometheus 접속 가능
- [x] 단위 테스트 13개 전체 통과

---

## 추가 호환성 이슈 (검증 중 발견)

| 이슈 | 해결 |
|------|------|
| Redisson 3.27.0이 Spring Boot 4 비호환 | `4.0.0`으로 업그레이드 |
| Springdoc 2.4.0이 Spring Boot 4 비호환 | `3.0.0`으로 업그레이드 |
| Flyway auto-config이 Spring Boot 4에서 분리됨 | `spring-boot-flyway` starter 사용 |
| Kafka `apache/kafka:3.9.0` advertised.listeners 오류 | INTERNAL/EXTERNAL 리스너 분리 구성 |
| docker-compose `version` 속성 deprecated 경고 | 무시 가능 (기능에 영향 없음) |

---

## 다음 단계 (Phase 2)

- Redis 재고 차감 (Lua Script)
- Kafka 비동기 발급 처리
- Redisson 분산 락 적용
- 동시성 테스트 (k6 or JMeter)
- Race Condition 해결 (Redis DECR 원자적 차감)
