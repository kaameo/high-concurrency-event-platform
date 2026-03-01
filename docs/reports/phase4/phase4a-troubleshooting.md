# Phase 4-A 트러블슈팅 리포트

## 환경

| 항목 | 버전 |
|------|------|
| Spring Boot | 4.0.3 |
| Resilience4j | 2.2.0 |
| Testcontainers | 2.0.3 (BOM) / 1.19.7 (선언) |
| Java | 21 |

---

## 이슈 1: Resilience4j Circuit Breaker `ignoreExceptions` 미동작

### 증상

`application.yaml`에 `ignore-exceptions`를 설정했으나 비즈니스 예외(`CouponSoldOutException` 등)가 여전히 Circuit Breaker에 의해 잡혀 fallback이 호출됨.
- 기대: `CouponSoldOutException` → 410 GONE
- 실제: fallback 호출 → 503 SERVICE_UNAVAILABLE

### 시도한 접근법과 결과

| # | 접근법 | 결과 | 원인 |
|---|--------|------|------|
| 1 | YAML `ignore-exceptions` | 미동작 | Spring Boot 4 프로퍼티 바인딩 호환 문제 |
| 2 | `CircuitBreakerConfigCustomizer` 빈 | 미동작 | `resilience4j-spring-boot3` auto-config이 Customizer를 무시 |
| 3 | 커스텀 `CircuitBreakerRegistry` 빈 | 미동작 | 스타터의 registry가 우선 적용 |
| 4 | fallback에서 비즈니스 예외 re-throw | 동작하지만 **편법** | 예외가 failure로 카운트되어 circuit이 불필요하게 열릴 수 있음 |

#### 접근법 1: YAML 설정

```yaml
# Spring Boot 4.0.3에서 동작하지 않음
resilience4j:
  circuitbreaker:
    instances:
      couponIssue:
        ignore-exceptions:
          - com.kaameo.event_platform.coupon.exception.CouponSoldOutException
```

#### 접근법 2: CircuitBreakerConfigCustomizer

```java
// Spring Boot 4에서 동작하지 않음
@Bean
public CircuitBreakerConfigCustomizer couponIssueCircuitBreakerCustomizer() {
    return CircuitBreakerConfigCustomizer.of("couponIssue", builder ->
            builder.ignoreExceptions(CouponSoldOutException.class));
}
```

`resilience4j-spring-boot3` 스타터의 auto-configuration이 Spring Boot 4의 변경된 빈 라이프사이클과 충돌. Customizer 빈이 registry에 반영되기 전에 `@CircuitBreaker` AOP가 기본 config으로 인스턴스를 생성.

#### 접근법 3: 커스텀 CircuitBreakerRegistry 빈

`resilience4j-spring-boot3` 스타터가 자체 `CircuitBreakerRegistry` 빈을 `@ConditionalOnMissingBean`이 아닌 방식으로 등록하여 커스텀 빈이 무시됨.

#### 접근법 4: fallback에서 re-throw (편법)

```java
private CouponIssueResponse issueCouponFallback(..., Throwable t) {
    if (t instanceof CouponSoldOutException) throw (RuntimeException) t;  // 편법!
    throw new ServiceUnavailableException("...");
}
```

동작은 하지만 비즈니스 예외가 circuit breaker의 failure rate에 카운트되므로, 정상적인 비즈니스 흐름(재고 소진)이 circuit을 open시킬 수 있음.

### 최종 해결: 프로그래매틱 CircuitBreaker + 직접 호출

`resilience4j-spring-boot3` 스타터 대신 `resilience4j-circuitbreaker` **코어 모듈만** 사용하고, 어노테이션 AOP 대신 `executeSupplier()`를 직접 호출.

**1. 의존성 변경**

```kotlin
// build.gradle.kts
// BEFORE (Spring Boot 4에서 ignoreExceptions 미동작)
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

// AFTER (정상 동작)
implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
```

**2. Config 빈 등록**

```java
@Configuration
public class CircuitBreakerConfig {

    @Bean
    public CircuitBreaker couponIssueCircuitBreaker() {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowType(SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .slowCallDurationThreshold(Duration.ofSeconds(3))
                        .slowCallRateThreshold(80)
                        .ignoreExceptions(
                                CouponSoldOutException.class,
                                CouponNotFoundException.class,
                                DuplicateIssueException.class,
                                RateLimitExceededException.class
                        )
                        .build();

        return CircuitBreaker.of("couponIssue", config);
    }
}
```

**3. Service에서 직접 호출**

```java
// BEFORE: 어노테이션 방식
@CircuitBreaker(name = "couponIssue", fallbackMethod = "fallback")
public CouponIssueResponse issueCoupon(...) { ... }

// AFTER: 프로그래매틱 방식
private final CircuitBreaker couponIssueCircuitBreaker;

public CouponIssueResponse issueCoupon(CouponIssueRequest request, String idempotencyKey) {
    try {
        return couponIssueCircuitBreaker.executeSupplier(
                () -> doIssueCoupon(request, idempotencyKey));
    } catch (CallNotPermittedException e) {
        throw new ServiceUnavailableException("서비스가 일시적으로 불안정합니다.");
    }
}
```

**4. 단위 테스트 수정**

`@InjectMocks` 대신 `@BeforeEach`에서 실제 `CircuitBreaker` 인스턴스를 주입:

```java
@BeforeEach
void setUp() {
    CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("couponIssue");
    couponIssueService = new CouponIssueService(
            couponIssueRepository, redisIdempotencyManager,
            redisRateLimiter, redisDuplicateChecker,
            redisStockManager, couponIssueProducer, circuitBreaker);
}
```

### 교훈

1. **Spring Boot 4 + Resilience4j 2.x 조합에서는 코어 모듈 직접 사용이 안정적.** spring-boot3 스타터의 auto-configuration이 Spring Boot 4와 완전히 호환되지 않음.
2. **프로그래매틱 방식이 어노테이션 방식보다 명시적이고 디버깅이 쉬움.** `ignoreExceptions`가 실제로 적용되는지 코드에서 바로 확인 가능.
3. **`ignoreExceptions`는 해당 예외를 failure로 카운트하지 않고 fallback도 호출하지 않음.** fallback에서 re-throw하는 방식은 예외가 failure rate에 카운트되므로 circuit이 불필요하게 열릴 수 있음.

---

## 이슈 2: Testcontainers `@ServiceConnection` + Spring Boot 4 호환

### 증상

Testcontainers를 `@ServiceConnection`으로 전환 시 `KafkaContainer`에서 다음 에러 발생:

```
ConnectionDetailsNotFoundException: No ConnectionDetails found for source
'@ServiceConnection source for Bean 'kafkaContainer''.
You may need to add a 'name' to your @ServiceConnection annotation
```

### 원인

Spring Boot 4.0.3의 `spring-boot-testcontainers` 모듈에서 `KafkaContainer`에 대한 `ConnectionDetailsFactory`가 등록되어 있지 않음. PostgreSQL(`JdbcConnectionDetails`)과 Redis(`DataRedisConnectionDetails`)는 지원하지만, Kafka는 자동 매핑이 불가.

### 시도한 접근법

| # | 접근법 | 결과 |
|---|--------|------|
| 1 | `@ServiceConnection` (name 없이) | `ConnectionDetailsNotFoundException` |
| 2 | `@ServiceConnection(name = "kafka")` | 동일 에러 — ConnectionDetailsFactory 자체가 없음 |
| 3 | **`DynamicPropertyRegistrar` 빈** | **정상 동작** |

### 해결: 혼합 방식 (ServiceConnection + DynamicPropertyRegistrar)

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

    @Bean
    @ServiceConnection  // 자동 바인딩 지원
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("event_platform_test")
                .withUsername("test").withPassword("test");
    }

    @Bean
    @ServiceConnection(name = "redis")  // GenericContainer이므로 name 필수
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }

    @Bean  // @ServiceConnection 미지원 → DynamicPropertyRegistrar로 대체
    public KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
    }

    @Bean
    public DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
        return registry -> registry.add(
                "spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
```

### 기존 방식 vs 신규 방식 비교

| 항목 | Before (`@DynamicPropertySource`) | After (`@ServiceConnection` + `@Import`) |
|------|-----------------------------------|------------------------------------------|
| 컨테이너 선언 | 각 테스트 클래스에 `static` 필드 중복 | 공유 `@TestConfiguration` 한 곳 |
| 프로퍼티 매핑 | 수동 `registry.add(...)` | PostgreSQL/Redis 자동, Kafka만 수동 |
| 테스트 적용 | `@Testcontainers` + `@Container` | `@Import(TestContainersConfiguration.class)` |
| Redisson 제외 | `@DynamicPropertySource`에서 설정 | `@SpringBootTest(properties = ...)` |
| 코드 중복 | 높음 (테스트마다 ~15줄 반복) | 없음 |

### Spring Boot 4 Testcontainers `@ServiceConnection` 지원 현황

| 컨테이너 | `@ServiceConnection` | 비고 |
|----------|----------------------|------|
| `PostgreSQLContainer` | O | `JdbcConnectionDetails` 자동 생성 |
| `GenericContainer` (Redis) | O | `name = "redis"` 필수 |
| `KafkaContainer` | **X** | `DynamicPropertyRegistrar` 사용 |
| `MongoDBContainer` | O | — |
| `Neo4jContainer` | O | — |
| `RabbitMQContainer` | O | — |

### 교훈

1. **Spring Boot 4에서 `@ServiceConnection`은 모든 컨테이너를 지원하지 않음.** Kafka처럼 미지원 컨테이너는 `DynamicPropertyRegistrar` 빈 방식으로 대체.
2. **`GenericContainer` 사용 시 `name` 속성 필수.** Spring Boot가 Docker 이미지 이름을 타입에서 추론할 수 없으므로 힌트가 필요.
3. **공유 `@TestConfiguration` + `@Import` 패턴이 중복을 제거하는 표준 방식.** `@DynamicPropertySource` + `@Testcontainers`는 레거시 방식.
4. **`spring-boot-testcontainers` 의존성 필수.** 기본 Spring Boot Starter에는 포함되지 않으므로 `build.gradle.kts`에 별도 추가 필요.

---

## 관련 파일

| 파일 | 설명 |
|------|------|
| `build.gradle.kts` | `resilience4j-circuitbreaker`, `spring-boot-testcontainers` 의존성 |
| `src/main/java/.../config/CircuitBreakerConfig.java` | 프로그래매틱 Circuit Breaker 설정 |
| `src/main/java/.../coupon/service/CouponIssueService.java` | `executeSupplier()` 적용 |
| `src/main/java/.../common/exception/ServiceUnavailableException.java` | 503 예외 클래스 |
| `src/main/java/.../common/exception/GlobalExceptionHandler.java` | 503 핸들러 |
| `src/test/java/.../TestContainersConfiguration.java` | 공유 Testcontainers 설정 |
| `src/test/java/.../EventPlatformApplicationTests.java` | `@Import` 적용 |
| `src/test/java/.../coupon/CouponIssueIntegrationTest.java` | `@Import` 리팩토링 |
| `src/test/java/.../coupon/CouponIssueServiceTest.java` | CircuitBreaker 수동 주입 |
