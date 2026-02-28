# Phase 1: 현업 표준 적합성 분석

- **작성일**: 2026-02-28
- **최종 수정일**: 2026-02-28
- **분석 대상**: Phase 1 전체 구현 (인프라, 도메인, API, 테스트)
- **환경**: Spring Boot 4.0.3, Java 21, PostgreSQL 16, Redis 7, Kafka 3.9

---

## 요약

Phase 1의 전반적인 구조(패키지 구성, Record DTO, Flyway, 멱등성 지원, UUID v7 PK)는 현업 표준에 부합. 그러나 고동시성 시스템의 핵심인 **재고 차감 로직에 Race Condition**이 존재하며, 보안/운영 측면에서 개선이 필요.

---

## Critical (P0) — 즉시 수정 필요

### 1. 재고 체크 Race Condition

**파일**: `CouponIssueService.java`

```java
long issuedCount = couponIssueRepository.countByCouponEventId(...)  // READ
if (issuedCount >= event.getTotalStock())                            // CHECK
    throw ...
couponIssueRepository.save(issue);                                   // ACT
```

**문제**: 전형적인 check-then-act 패턴. 동시 요청 시 여러 스레드가 동일한 `issuedCount`를 읽고 모두 통과하여 **초과 발급** 발생. `@Transactional`의 기본 `READ COMMITTED` 격리 수준으로는 방지 불가.

**해결 옵션**:

| 방식 | 장점 | 단점 |
|------|------|------|
| Redis `DECR` (Phase 2 예정) | O(1) 원자적, 수평 확장 가능, 업계 표준 | Redis 장애 시 정합성 관리 필요 |
| `SELECT FOR UPDATE` (Phase 1 임시) | 순수 RDBMS, 단순한 정합성 | 행 수준 직렬화로 병목 발생 |
| 낙관적 락 `@Version` | DB 락 없음 | 높은 경합 시 재시도 폭발 |

**권장**: Phase 1에서는 `SELECT FOR UPDATE`로 최소 방어, Phase 2에서 Redis `DECR`로 교체.

---

### 2. Idempotency Key 충돌 미처리

**파일**: `CouponIssueService.java`

**문제**: 동시에 같은 Idempotency-Key로 요청이 들어오면, 두 요청 모두 `findByIdempotencyKey`에서 `empty`를 받고 INSERT를 시도. UNIQUE 제약 위반으로 `DataIntegrityViolationException` → 500 에러.

**해결**: `DataIntegrityViolationException` catch 후 기존 레코드 조회하여 반환.

---

### 3. Kafka trusted.packages 와일드카드

**파일**: `application.yaml`

```yaml
spring.json.trusted.packages: "*"
```

**문제**: 모든 패키지를 신뢰하여 Kafka JSON 역직렬화 시 **원격 코드 실행(RCE)** 취약점 노출.

**해결**: `com.kaameo.event_platform.**`로 제한.

---

## High (P1) — 빠른 시일 내 수정

### 4. 하드코딩된 자격증명

**파일**: `application.yaml`

**문제**: 소스코드에 자격증명 직접 기입. Git 이력에 영구 노출.

**해결**: 환경변수 참조로 교체.
```yaml
username: ${SPRING_DATASOURCE_USERNAME:postgres}
password: ${SPRING_DATASOURCE_PASSWORD:postgres}
```

---

### 5. CouponEvent에 @Version 없음

**파일**: `CouponEvent.java`

**문제**: 동시에 이벤트 상태를 변경(ACTIVE → EXPIRED 등)하면 Lost Update 발생 가능.

**해결**: `@Version private Long version;` 필드 추가.

---

### 6. Docker health check 없음

**파일**: `docker-compose.yml`

**문제**: `depends_on`만으로는 DB가 실제 쿼리를 받을 준비가 되었는지 보장하지 않음.

**해결 예시**:
```yaml
postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U postgres"]
    interval: 5s
    timeout: 5s
    retries: 5
```

---

### 7. GlobalExceptionHandler 로깅 없음

**파일**: `GlobalExceptionHandler.java`

**문제**: 500 에러 시 예외가 로그에 남지 않아 운영 환경에서 디버깅 불가.

**해결**: `log.error("Unhandled exception", e)` 추가.

---

## Medium (P2) — 개선 권장

### 8. TIMESTAMP → TIMESTAMPTZ

**파일**: `V1__init.sql`, `V2__uuid_v7_migration.sql`, Entity 클래스

**문제**: `TIMESTAMP`(타임존 없음) + `LocalDateTime`(타임존 없음) 조합. 멀티 리전 환경에서 시간 불일치 버그 발생 가능.

**권장**: `TIMESTAMPTZ` + `Instant` 또는 `OffsetDateTime`.

---

### 9. HikariCP 커넥션 풀 미설정

**파일**: `application.yaml`

**문제**: 기본값 max 10개 커넥션. 고동시성 시스템에서는 부족.

**권장 설정**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
      connection-timeout: 3000
      idle-timeout: 600000
```

---

### 10. Rate Limiting 없음

**파일**: `CouponController.java`

**문제**: 쿠폰 발급 엔드포인트에 요청 제한이 없어 어뷰징 가능.

**권장**: Bucket4j 또는 Redis 기반 Rate Limiter 적용 (Phase 2).

---

### 11. Health Endpoint 정보 노출

**파일**: `application.yaml`

```yaml
show-details: always
```

**문제**: DB 버전, Redis 호스트, 디스크 용량 등 인프라 정보가 비인증 사용자에게 노출.

**해결**: `when_authorized`로 변경.

---

### 12. Entity timestamp 수동 관리

**파일**: `CouponEvent.java`

**문제**: `this.createdAt = LocalDateTime.now()` 수동 설정. `updatedAt`은 이후 수정 시 갱신되지 않음.

**권장**: JPA 콜백 사용.
```java
@PrePersist
protected void onCreate() {
    this.createdAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
}

@PreUpdate
protected void onUpdate() {
    this.updatedAt = LocalDateTime.now();
}
```

---

## 현업 표준 적합 항목 (잘된 부분)

| 항목 | 평가 | 비고 |
|------|------|------|
| **UUID v7 PK (네이티브 uuid)** | **우수** | RFC 9562 표준, 16바이트 네이티브, 시간 순 정렬, 분산 생성 |
| Record 기반 DTO | 우수 | Java 21 모범 사례 |
| `@NoArgsConstructor(PROTECTED)` + `@Builder` | 우수 | JPA Entity 표준 패턴 |
| `open-in-view: false` | 우수 | N+1 방지 모범 사례 |
| `UUID couponEventId` (비정규화) | 우수 | 고동시성 시스템에서 `@ManyToOne` 회피는 의도적 설계 |
| `Idempotency-Key` 헤더 | 우수 | 결제/발급 시스템 현업 표준 |
| `202 Accepted` 응답 | 우수 | 비동기 전환(Phase 2) 고려한 설계 |
| Flyway 마이그레이션 | 우수 | DB 스키마 관리 표준 |
| Versioned API (`/api/v1/`) | 우수 | REST API 관례 준수 |
| Virtual Threads 활성화 | 우수 | Java 21 고동시성 활용 |
| Prometheus + Actuator | 우수 | 운영 메트릭 표준 스택 |
| UUID PathVariable 타입 바인딩 | 우수 | Spring의 자동 타입 변환 활용, 유효하지 않은 UUID 자동 400 |

---

## Phase별 해결 계획

| 이슈 | Phase 1 수정 | Phase 2 해결 |
|------|-------------|-------------|
| Race Condition | `SELECT FOR UPDATE` 임시 적용 | Redis `DECR` 원자적 차감 |
| Idempotency 충돌 | `DataIntegrityViolationException` 핸들링 | — |
| trusted.packages | 즉시 수정 | — |
| 자격증명 외부화 | 환경변수 참조 | Spring Cloud Config |
| @Version | 즉시 추가 | — |
| Docker health check | 즉시 추가 | — |
| 로깅 | 즉시 추가 | — |
| Rate Limiting | — | Redis Rate Limiter |
| HikariCP 튜닝 | — | 부하 테스트 후 최적화 |
