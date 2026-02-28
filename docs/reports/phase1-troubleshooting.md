# Phase 1: 트러블슈팅 기록

- **작성일**: 2026-02-28
- **최종 수정일**: 2026-02-28
- **환경**: Spring Boot 4.0.3, Java 21, Gradle 9.3.1

---

## 1. Flyway Auto-Configuration 누락

### 증상
```
Schema validation: missing table [coupon_event]
```
Flyway가 실행되지 않아 DB 테이블이 생성되지 않고, Hibernate `ddl-auto: validate`에서 테이블 없음 오류 발생.

### 원인
Spring Boot 4.0에서 **모듈화(Modularization)** 작업이 진행되면서, 기존에 `spring-boot-autoconfigure` 안에 포함되었던 Flyway auto-configuration이 별도 모듈로 분리됨. `flyway-core`만으로는 auto-configuration이 동작하지 않음.

### 해결
```kotlin
// AS-IS (Spring Boot 3.x)
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")

// TO-BE (Spring Boot 4.x)
implementation("org.springframework.boot:spring-boot-flyway")
runtimeOnly("org.flywaydb:flyway-database-postgresql")
```

### 검증
```
Migrating schema "public" to version "1 - init"
Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.016s)
```

### 참고
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Flyway Migrations in Spring Boot 4.x](https://pranavkhodanpur.medium.com/flyway-migrations-in-spring-boot-4-x-what-changed-and-how-to-configure-it-correctly-dbe290fa4d47)
- [Spring Boot 4 Modularization](https://www.danvega.dev/blog/2025/12/12/spring-boot-4-modularization)

---

## 2. Redisson 3.27.0 Spring Boot 4 비호환

### 증상
```
java.lang.IllegalStateException: Failed to generate bean name for imported class
  'org.redisson.spring.starter.RedissonAutoConfigurationV2'
Caused by: java.lang.ClassNotFoundException:
  org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
```

### 원인
Redisson 3.27.0의 `RedissonAutoConfigurationV2`가 `@AutoConfigureBefore(RedisAutoConfiguration.class)`로 Spring Boot의 `RedisAutoConfiguration`을 참조하는데, Spring Boot 4에서 이 클래스의 패키지가 변경됨 (모듈화).

### 해결
```kotlin
// AS-IS
implementation("org.redisson:redisson-spring-boot-starter:3.27.0")

// TO-BE
implementation("org.redisson:redisson-spring-boot-starter:4.0.0")
```

### 참고
- [Redisson Spring Boot 4 support issue](https://github.com/redisson/redisson/issues/6869)
- [Redisson 4.0.0 release](https://central.sonatype.com/artifact/org.redisson/redisson-spring-boot-starter/4.0.0)

---

## 3. Apache Kafka Docker `advertised.listeners` 오류

### 증상
```
java.lang.IllegalArgumentException: requirement failed:
  advertised.listeners cannot use the nonroutable meta-address 0.0.0.0.
  Use a routable IP address.
```

### 원인
Kafka 3.9.0에서 KRaft 모드 리스너 검증 강화. `advertised.listeners`에 `0.0.0.0` 사용 불가.

### 해결
내부(Docker 네트워크)와 외부(호스트) 리스너를 분리:
```yaml
KAFKA_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://0.0.0.0:9092,CONTROLLER://kafka:9093
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT,CONTROLLER:PLAINTEXT
KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
```

### 참고
- [Kafka Docker Examples](https://github.com/apache/kafka/blob/trunk/docker/examples/README.md)
- [Confluent - Why Can't I Connect to Kafka?](https://www.confluent.io/blog/kafka-client-cannot-connect-to-broker-on-aws-on-docker-etc/)

---

## 4. Springdoc OpenAPI 2.x Spring Boot 4 비호환

### 증상
```
BeanCreationException: Error creating bean with name
  'queryDslQuerydslPredicateOperationCustomizer'
```

### 원인
Springdoc OpenAPI 2.x는 Spring Boot 3.x 전용. Spring Framework 7.x 내부 API 변경으로 호환 불가.

### 해결
```kotlin
// AS-IS
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.4.0")

// TO-BE
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")
```

### 참고
- [Springdoc Spring Boot 4 support issue](https://github.com/springdoc/springdoc-openapi/issues/3062)

---

## 5. QueryDSL Annotation Processor `NoClassDefFoundError`

### 증상
```
java.lang.NoClassDefFoundError: jakarta/persistence/Entity
```

### 원인
`querydsl-apt:5.1.0:jakarta` AP 실행 시 `jakarta.persistence` 패키지가 AP 클래스패스에 없음.

### 해결
```kotlin
annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")
annotationProcessor("jakarta.persistence:jakarta.persistence-api")
annotationProcessor("jakarta.annotation:jakarta.annotation-api")
```

---

## 6. `@WebMvcTest`, `@AutoConfigureMockMvc` 패키지 변경

### 증상
```
error: package org.springframework.boot.test.autoconfigure.web.servlet does not exist
```

### 원인
Spring Boot 4 모듈화로 테스트 클래스 패키지 이동.

### 해결
```java
// AS-IS
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

// TO-BE
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
```

---

## 7. `@WebMvcTest`에서 `ObjectMapper` 자동 주입 불가

### 증상
```
NoSuchBeanDefinitionException: No qualifying bean of type 'ObjectMapper' available
```

### 원인
Spring Boot 4 모듈화로 Jackson auto-configuration이 `@WebMvcTest` 슬라이스에 미포함.

### 해결
```java
// AS-IS
@Autowired private ObjectMapper objectMapper;

// TO-BE
private final ObjectMapper objectMapper = new ObjectMapper();
```

---

## 8. PK 타입 전환: BIGSERIAL → ULID → UUID v7

### 경위
1. 초기 PRD에서 `BIGSERIAL` (auto-increment) PK로 설계
2. 분산 환경 대비를 위해 ULID (`CHAR(26)`)로 1차 전환
3. PostgreSQL 성능 분석 후 UUID v7 (`uuid` 네이티브 타입)으로 최종 전환

### ULID의 문제점 (char(26) 저장 시)
- PostgreSQL에 네이티브 타입 없음 → `char(26)` 문자열로 저장
- 인덱스 크기: 26 bytes/entry (uuid 16 bytes 대비 **62% 더 큼**)
- B-tree 비교: 콜레이션 기반 문자열 비교 (바이너리 대비 느림)
- FK JOIN: 26바이트 문자열 비교
- WAL 볼륨: PK당 62% 더 많은 복제 트래픽
- JPA 매핑: `String` 타입 (타입 안전성 상실)

### UUID v7 선택 근거
- RFC 9562 국제 표준 (IETF)
- PostgreSQL `uuid` 네이티브 타입 (16 bytes)
- 시간 순 정렬 보장 (48-bit ms timestamp)
- monotonic random (동일 밀리초 내 순서 보장)
- JPA `UUID` 타입 네이티브 지원
- `uuid-creator` 라이브러리: `UuidCreator.getTimeOrderedEpoch()`

### 변경 파일
- `build.gradle.kts` — `ulid-creator:5.2.3` → `uuid-creator:6.0.0`
- `V2__uuid_v7_migration.sql` — `CHAR(26)` → `UUID` 네이티브
- Entity 2개 — `String id` → `UUID id`
- Repository 2개 — `JpaRepository<*, String>` → `JpaRepository<*, UUID>`
- DTO 4개 — `String` → `UUID`
- Service 2개, Controller — `UUID` 타입 파라미터
- Test 3개 — `UUID.fromString()` 기반

### 검증
- `./gradlew compileJava` → BUILD SUCCESSFUL
- 단위 테스트 13개 → ALL PASSED

---

## 요약: Spring Boot 4.x 마이그레이션 체크리스트

| # | 항목 | 필요 조치 |
|---|------|-----------|
| 1 | Flyway | `flyway-core` → `spring-boot-flyway` starter 교체 |
| 2 | Redisson | 3.x → 4.0.0+ 업그레이드 |
| 3 | Springdoc | 2.x → 3.0.0 업그레이드 |
| 4 | QueryDSL AP | `jakarta.persistence-api`, `jakarta.annotation-api` AP에 추가 |
| 5 | 테스트 import | `boot.test.autoconfigure.web.servlet` → `boot.webmvc.test.autoconfigure` |
| 6 | ObjectMapper | `@WebMvcTest`에서 수동 생성 필요 |
| 7 | Kafka Docker | 리스너 분리 구성 (INTERNAL/EXTERNAL) |
| 8 | PK 타입 | 분산 환경 시 UUID v7 (`uuid` 네이티브) 권장 |
