# High-Concurrency Event Platform

선착순 쿠폰 발급 시스템 — 대규모 동시 요청을 안정적으로 처리하는 이벤트 플랫폼

## 기술 스택

| 분류 | 기술 | 버전 |
|------|------|------|
| **Language** | Java | 21 (Virtual Threads) |
| **Framework** | Spring Boot | 4.0.3 |
| **Database** | PostgreSQL | 16 |
| **Cache** | Redis | 7 |
| **Message Broker** | Apache Kafka (KRaft) | 3.9.0 |
| **ORM** | Hibernate | 7.x |
| **Migration** | Flyway | 11.x |
| **API Docs** | Springdoc OpenAPI | 3.0.0 |
| **Monitoring** | Prometheus + Grafana | latest |
| **Build** | Gradle (Kotlin DSL) | 9.3.1 |
| **Test** | JUnit 5, Mockito, Testcontainers | — |

---

## 프로젝트 구조

```
high-concurrency-event-platform/
├── build.gradle.kts
├── docker-compose.yml
├── .env.example
├── infra/
│   └── prometheus/
│       └── prometheus.yml
├── docs/
│   ├── prd/                          # 제품 요구사항 문서
│   ├── plan/                         # Phase별 구현 계획
│   └── reports/                      # 진행 보고서, 트러블슈팅
├── src/
│   ├── main/
│   │   ├── java/com/kaameo/event_platform/
│   │   │   ├── EventPlatformApplication.java
│   │   │   ├── config/
│   │   │   │   └── SwaggerConfig.java
│   │   │   ├── common/
│   │   │   │   ├── dto/
│   │   │   │   │   └── ApiResponse.java
│   │   │   │   └── exception/
│   │   │   │       └── GlobalExceptionHandler.java
│   │   │   └── coupon/
│   │   │       ├── domain/
│   │   │       │   ├── CouponEvent.java
│   │   │       │   ├── CouponIssue.java
│   │   │       │   ├── CouponEventStatus.java
│   │   │       │   └── IssueStatus.java
│   │   │       ├── repository/
│   │   │       │   ├── CouponEventRepository.java
│   │   │       │   └── CouponIssueRepository.java
│   │   │       ├── dto/
│   │   │       │   ├── CouponIssueRequest.java
│   │   │       │   ├── CouponIssueResponse.java
│   │   │       │   ├── CouponDetailResponse.java
│   │   │       │   └── CouponIssueStatusResponse.java
│   │   │       ├── service/
│   │   │       │   ├── CouponService.java
│   │   │       │   └── CouponIssueService.java
│   │   │       ├── controller/
│   │   │       │   └── CouponController.java
│   │   │       └── exception/
│   │   │           ├── CouponNotFoundException.java
│   │   │           ├── CouponSoldOutException.java
│   │   │           └── DuplicateIssueException.java
│   │   └── resources/
│   │       ├── application.yaml
│   │       └── db/migration/
│   │           └── V1__init.sql
│   └── test/
│       └── java/com/kaameo/event_platform/coupon/
│           ├── CouponControllerTest.java
│           ├── CouponIssueServiceTest.java
│           └── CouponIssueIntegrationTest.java
```

---

## 빠른 시작

### 사전 요구사항

- Java 21+
- Docker & Docker Compose
- Gradle 9.x (Wrapper 포함)

### 1. 환경변수 설정

```bash
cp .env.example .env
```

`.env` 파일을 필요에 따라 수정합니다. 기본값으로도 로컬 개발이 가능합니다.

### 2. 인프라 기동

```bash
docker-compose up -d
```

6개 컨테이너가 기동됩니다:

| 서비스 | 설명 | URL |
|--------|------|-----|
| PostgreSQL | 메인 데이터베이스 | `localhost:5432` |
| Redis | 캐시 및 분산 락 | `localhost:6379` |
| Kafka | 메시지 브로커 (KRaft) | `localhost:9092` |
| Kafka UI | Kafka 클러스터 관리 UI | [http://localhost:9091](http://localhost:9091) |
| Prometheus | 메트릭 수집 | [http://localhost:9090](http://localhost:9090) |
| Grafana | 메트릭 대시보드 | [http://localhost:3000](http://localhost:3000) |

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

기동 완료 후 다음 URL에 접속할 수 있습니다:

| 서비스 | URL | 비고 |
|--------|-----|------|
| **Test Console** | [http://localhost:8080](http://localhost:8080) | 쿠폰 발급 테스트 UI |
| **Swagger UI** | [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html) | API 문서 및 테스트 |
| **OpenAPI JSON** | [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs) | OpenAPI 3.1 스펙 |
| **Health Check** | [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) | 애플리케이션 상태 |
| **Prometheus Metrics** | [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus) | 메트릭 엔드포인트 |

### 4. 테스트 실행

```bash
# 단위 테스트 (Docker 불필요)
./gradlew test --tests "*.CouponControllerTest" --tests "*.CouponIssueServiceTest"

# 통합 테스트 (Docker 필요 — Testcontainers 사용)
./gradlew test --tests "*.CouponIssueIntegrationTest"

# 전체 테스트
./gradlew test
```

---

## API 명세

### 공통 응답 포맷

```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "meta": null
}
```

### POST /api/v1/coupons/issue

쿠폰 발급 요청 (비동기)

**Headers:**

| Header | 필수 | 설명 |
|--------|------|------|
| `Idempotency-Key` | Yes | 클라이언트 생성 멱등성 키 (UUID 권장) |
| `Content-Type` | Yes | `application/json` |

**Request Body:**

```json
{
  "couponEventId": 1,
  "userId": 12345
}
```

**Response (202 Accepted):**

```json
{
  "success": true,
  "data": {
    "requestId": "c0d395c9-d2f7-47da-b58a-e45765c93987",
    "status": "ISSUED",
    "message": "쿠폰이 발급되었습니다."
  }
}
```

**에러 응답:**

| Status | 설명 |
|--------|------|
| 400 | 잘못된 요청 (필수 필드 누락) |
| 404 | 쿠폰 이벤트 없음 또는 비활성 |
| 409 | 동일 사용자 중복 발급 |
| 410 | 재고 소진 |

**cURL 예시:**

```bash
curl -X POST http://localhost:8080/api/v1/coupons/issue \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"couponEventId": 1, "userId": 12345}'
```

---

### GET /api/v1/coupons/requests/{requestId}

발급 요청 상태 조회

**Response (200 OK):**

```json
{
  "success": true,
  "data": {
    "requestId": "c0d395c9-d2f7-47da-b58a-e45765c93987",
    "couponEventId": 1,
    "userId": 12345,
    "status": "ISSUED",
    "issuedAt": "2026-02-28T18:39:03.132166"
  }
}
```

**status 값:**

| Status | 설명 |
|--------|------|
| `PENDING` | 처리 대기 중 |
| `ISSUED` | 발급 완료 |
| `REJECTED_OUT_OF_STOCK` | 재고 소진으로 거절 |
| `REJECTED_DUPLICATE` | 중복 요청으로 거절 |
| `FAILED` | 시스템 오류로 실패 |

**cURL 예시:**

```bash
curl http://localhost:8080/api/v1/coupons/requests/{requestId}
```

---

### GET /api/v1/coupons/{couponEventId}

쿠폰 이벤트 상세 조회

**Response (200 OK):**

```json
{
  "success": true,
  "data": {
    "couponEventId": 1,
    "name": "신규 가입 쿠폰",
    "totalStock": 100000,
    "remainingStock": 99999,
    "status": "ACTIVE",
    "startAt": "2026-02-28T08:38:51.314072",
    "endAt": "2026-03-01T09:38:51.314072"
  }
}
```

**cURL 예시:**

```bash
curl http://localhost:8080/api/v1/coupons/1
```

---

## DB 스키마

Flyway로 자동 마이그레이션됩니다 (`V1__init.sql`).

```sql
-- 쿠폰 이벤트
CREATE TABLE coupon_event (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    total_stock INTEGER NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, INACTIVE, EXPIRED
    start_at    TIMESTAMP NOT NULL,
    end_at      TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 쿠폰 발급
CREATE TABLE coupon_issue (
    id               BIGSERIAL PRIMARY KEY,
    request_id       UUID NOT NULL UNIQUE,
    coupon_event_id  BIGINT NOT NULL REFERENCES coupon_event(id),
    user_id          BIGINT NOT NULL,
    idempotency_key  VARCHAR(64) NOT NULL UNIQUE,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    issued_at        TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (coupon_event_id, user_id)  -- 1인 1쿠폰 제약
);
```

**테스트 데이터 삽입:**

```bash
docker exec high-concurrency-event-platform-postgres-1 \
  psql -U postgres -d event_platform -c \
  "INSERT INTO coupon_event (name, total_stock, status, start_at, end_at, created_at, updated_at)
   VALUES ('신규 가입 쿠폰', 100000, 'ACTIVE', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day', NOW(), NOW());"
```

---

## 모니터링

### Prometheus

- **URL**: [http://localhost:9090](http://localhost:9090)
- **Targets**: [http://localhost:9090/targets](http://localhost:9090/targets)
- Spring Boot Actuator에서 `/actuator/prometheus` 엔드포인트를 통해 메트릭 수집
- 수집 주기: 15초

**주요 메트릭:**

| 메트릭 | 설명 |
|--------|------|
| `http_server_requests_seconds` | HTTP 요청 응답 시간 |
| `jvm_memory_used_bytes` | JVM 메모리 사용량 |
| `hikaricp_connections_active` | 활성 DB 커넥션 수 |
| `process_cpu_usage` | CPU 사용률 |

### Grafana

- **URL**: [http://localhost:3000](http://localhost:3000)
- **기본 계정**: admin / admin (`.env`의 `GRAFANA_ADMIN_PASSWORD`)
- Prometheus를 데이터 소스로 추가하여 대시보드 구성

**데이터 소스 설정:**
1. Grafana 접속 → Configuration → Data Sources → Add data source
2. Prometheus 선택
3. URL: `http://prometheus:9090` (Docker 네트워크 내부)
4. Save & Test

### Kafka UI

- **URL**: [http://localhost:9091](http://localhost:9091)
- 토픽 목록, 메시지 조회, 컨슈머 그룹 모니터링
- Phase 2에서 Kafka 비동기 처리 도입 후 본격 활용

---

## 환경변수

`.env.example` 참조:

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `POSTGRES_DB` | `event_platform` | PostgreSQL 데이터베이스명 |
| `POSTGRES_USER` | `postgres` | PostgreSQL 사용자 |
| `POSTGRES_PASSWORD` | `postgres` | PostgreSQL 비밀번호 |
| `POSTGRES_PORT` | `5432` | PostgreSQL 포트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `REDIS_MAX_MEMORY` | `256mb` | Redis 최대 메모리 |
| `KAFKA_PORT` | `9092` | Kafka 브로커 포트 |
| `KAFKA_NUM_PARTITIONS` | `1` | Kafka 기본 파티션 수 |
| `KAFKA_UI_PORT` | `9091` | Kafka UI 포트 |
| `APP_PORT` | `8080` | 애플리케이션 포트 |
| `PROMETHEUS_PORT` | `9090` | Prometheus 포트 |
| `GRAFANA_PORT` | `3000` | Grafana 포트 |
| `GRAFANA_ADMIN_PASSWORD` | `admin` | Grafana 관리자 비밀번호 |

---

## 아키텍처

### Phase 1 (현재) — 기반 구축

```
Client → Spring Boot (Virtual Threads)
              ├── CouponController (REST API)
              ├── CouponIssueService (발급 로직)
              ├── CouponService (조회 로직)
              └── PostgreSQL (직접 저장)
```

- 동기 처리: 요청 → DB 저장 → 응답
- 멱등성: `Idempotency-Key` 헤더 + DB UNIQUE 제약
- 중복 방지: `(coupon_event_id, user_id)` UNIQUE 제약

### Phase 2 (예정) — 고동시성 처리

```
Client → Spring Boot
              ├── Redis (원자적 재고 차감, DECR)
              ├── Kafka (비동기 발급 이벤트)
              └── Consumer → PostgreSQL (최종 저장)
```

- Redis: 재고를 원자적으로 차감하여 Race Condition 방지
- Kafka: 발급 이벤트를 비동기로 처리하여 처리량 극대화
- Redisson: 분산 락으로 추가 동시성 제어

### Phase 3 (예정) — 운영 안정화

- Kubernetes 배포 (kind 클러스터)
- HPA (Horizontal Pod Autoscaler)
- k6/JMeter 부하 테스트
- Grafana 대시보드

---

## 주요 설계 결정

| 결정 | 이유 |
|------|------|
| `202 Accepted` 응답 | Phase 2 비동기 전환 대비 |
| `Long couponEventId` (FK 대신 ID 직접 저장) | 고동시성 환경에서 JPA `@ManyToOne` JOIN 회피 |
| Record 기반 DTO | Java 21 불변 객체, 보일러플레이트 최소화 |
| Virtual Threads | Java 21 경량 스레드로 동시 처리량 증대 |
| Flyway | DB 스키마 버전 관리 현업 표준 |
| `open-in-view: false` | 트랜잭션 범위 밖 지연 로딩 방지 (N+1 예방) |

---

## 문서

| 문서 | 경로 | 설명 |
|------|------|------|
| PRD | `docs/PRD.md` | 제품 요구사항 |
| Phase별 계획 | `docs/plan/` | 구현 계획 상세 |
| 진행 보고서 | `docs/reports/phase1-progress.md` | Phase 1 완료 항목 |
| 트러블슈팅 | `docs/reports/phase1-troubleshooting.md` | Spring Boot 4 호환성 이슈 7건 |
| 표준 분석 | `docs/reports/phase1-standards-review.md` | 현업 표준 적합성 분석 |

---

## 로컬 개발 URL 요약

| 서비스 | URL | 용도 |
|--------|-----|------|
| **Test Console** | [http://localhost:8080](http://localhost:8080) | 쿠폰 발급 테스트 UI |
| **Swagger UI** | [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html) | API 문서 및 테스트 |
| **OpenAPI Spec** | [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs) | OpenAPI JSON |
| **Health** | [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) | 앱 상태 확인 |
| **Prometheus Metrics** | [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus) | 앱 메트릭 |
| **Prometheus** | [http://localhost:9090](http://localhost:9090) | 메트릭 수집/쿼리 |
| **Prometheus Targets** | [http://localhost:9090/targets](http://localhost:9090/targets) | 수집 대상 상태 |
| **Grafana** | [http://localhost:3000](http://localhost:3000) | 대시보드 (admin/admin) |
| **Kafka UI** | [http://localhost:9091](http://localhost:9091) | Kafka 클러스터 관리 |
