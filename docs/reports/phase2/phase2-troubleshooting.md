# Phase 2: 트러블슈팅 기록

- **작성일**: 2026-02-28
- **최종 수정일**: 2026-02-28
- **환경**: Spring Boot 4.0.3, Java 21, Spring Batch 6.0.2, Gradle 9.3.1

---

## 1. Redisson AutoConfiguration 제외 실패 (Integration Test)

### 증상
```
java.lang.IllegalStateException: The following classes could not be excluded
because they are not auto-configuration classes:
  - org.redisson.spring.starter.RedissonAutoConfiguration
```
통합 테스트에서 `spring.autoconfigure.exclude`로 Redisson을 제외하려 했으나 실패.

### 원인
Redisson 4.0.0 (Spring Boot 4 호환 버전)에서 auto-configuration 클래스가 리팩토링됨. 기존 `RedissonAutoConfiguration` 클래스는 더 이상 `AutoConfiguration.imports`에 등록되지 않고, `RedissonAutoConfigurationV2`와 `RedissonAutoConfigurationV4`로 분리됨.

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:
  org.redisson.spring.starter.RedissonAutoConfigurationV2
  org.redisson.spring.starter.RedissonAutoConfigurationV4
```

### 해결
```java
// AS-IS (Redisson 3.x)
registry.add("spring.autoconfigure.exclude", () ->
    "org.redisson.spring.starter.RedissonAutoConfiguration");

// TO-BE (Redisson 4.x)
registry.add("spring.autoconfigure.exclude", () ->
    "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
    "org.redisson.spring.starter.RedissonAutoConfigurationV4");
```

### 교훈
- Spring Boot 4 호환 라이브러리들이 auto-config 클래스를 리팩토링하는 경우가 많음
- `jar tf` + `AutoConfiguration.imports` 파일 확인이 가장 확실한 디버깅 방법

---

## 2. ObjectMapper Bean 미등록 (Integration Test)

### 증상
```
NoSuchBeanDefinitionException: No qualifying bean of type
'com.fasterxml.jackson.databind.ObjectMapper' available
```
`@SpringBootTest` + `@AutoConfigureMockMvc` 환경에서 `@Autowired ObjectMapper` 실패.

### 원인
`spring-boot-starter-webmvc` (Spring Boot 4)에서 Jackson auto-configuration이 분리됨. `@WebMvcTest`에서는 자동 등록되지만, `@SpringBootTest`에서 특정 auto-config을 제외한 경우 Jackson이 함께 제외될 수 있음.

### 해결
```java
// AS-IS
@Autowired
private ObjectMapper objectMapper;

// TO-BE
private final ObjectMapper objectMapper = new ObjectMapper();
```

### 교훈
- 테스트에서 `ObjectMapper`는 프레임워크 빈에 의존하지 않고 직접 생성하는 것이 안전
- Phase 1 `CouponControllerTest`에서도 동일 패턴 사용 중이었음

---

## 3. Spring Batch 6 패키지 구조 변경

### 증상
```
error: package org.springframework.batch.item.database does not exist
error: cannot find symbol — class Job
error: cannot find symbol — class Step
```
Spring Batch 관련 import가 전부 컴파일 에러.

### 원인
Spring Batch 6.0에서 대규모 패키지 리팩토링 수행:

| 클래스 | Batch 5 (Spring Boot 3) | Batch 6 (Spring Boot 4) |
|--------|------------------------|------------------------|
| `Job` | `org.springframework.batch.core.Job` | `org.springframework.batch.core.job.Job` |
| `Step` | `org.springframework.batch.core.Step` | `org.springframework.batch.core.step.Step` |
| `ItemProcessor` | `org.springframework.batch.item.ItemProcessor` | `org.springframework.batch.infrastructure.item.ItemProcessor` |
| `ItemWriter` | `org.springframework.batch.item.ItemWriter` | `org.springframework.batch.infrastructure.item.ItemWriter` |
| `JpaPagingItemReader` | `org.springframework.batch.item.database.*` | `org.springframework.batch.infrastructure.item.database.*` |

### 해결
```java
// AS-IS (Batch 5)
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JpaPagingItemReader;

// TO-BE (Batch 6)
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
```

### 교훈
- Spring Batch 6은 `core` ↔ `infrastructure` 모듈 분리가 패키지 수준까지 반영됨
- `jar tf` 명령으로 실제 클래스 위치 확인 필수

---

## 4. StepBuilder.chunk() Deprecated

### 증상
```
warning: [removal] chunk(int, PlatformTransactionManager) in StepBuilder
has been deprecated and marked for removal
```

### 원인
Spring Batch 6에서 `StepBuilder.chunk(int, PlatformTransactionManager)` → `StepBuilder.chunk(int)` + `.transactionManager()` 체이닝 방식으로 변경. 새 API는 `ChunkOrientedStepBuilder`를 반환.

### 해결
```java
// AS-IS (Batch 5)
new StepBuilder("step", jobRepository)
    .<I, O>chunk(10, transactionManager)
    .reader(reader)
    .writer(writer)
    .build();

// TO-BE (Batch 6)
new StepBuilder("step", jobRepository)
    .<I, O>chunk(10)
    .reader(reader)
    .writer(writer)
    .transactionManager(transactionManager)
    .build();
```

### 교훈
- Batch 6의 빌더 패턴이 더 명시적 (transactionManager 별도 설정)
- `javap` 명령으로 API 시그니처 확인 가능

---

## 5. Lambda에서 effectively final 변수 필요

### 증상
```
error: Local variable event is required to be final or effectively final
```
통합 테스트에서 `event = couponEventRepository.save(event)` 후 lambda 내부에서 사용 시 컴파일 에러.

### 원인
`event` 변수를 재할당 (`event = ...save(event)`)하면 effectively final이 아니게 되어 lambda/anonymous class 내부에서 참조 불가.

### 해결
```java
// AS-IS
event = couponEventRepository.save(event);
// lambda에서 event 사용 → 컴파일 에러

// TO-BE
CouponEvent savedEvent = couponEventRepository.save(event);
// lambda에서 savedEvent 사용 → OK
```

---

## 6. k6 http_req_failed Threshold 실패 (Exit Code 99)

### 증상
k6 실행 완료 후 exit code 99 반환. 테스트 자체는 정상 동작했으나 threshold 실패로 비정상 종료.

### 원인
k6는 기본적으로 4xx 응답을 `http_req_failed`로 카운트. Phase 2에서는 409 (중복), 410 (재고 소진), 429 (Rate Limit) 응답이 정상 비즈니스 로직이지만, k6는 이를 실패로 집계.

```
http_req_failed: 23.31%  30408 out of 130408
```
→ 30,408건 = 중복 2,547 + 재고 소진 27,861

### 해결
`http_req_failed` threshold 제거. 비즈니스 성공률은 커스텀 `success_rate` 메트릭으로 별도 측정.

```javascript
// AS-IS
thresholds: {
  http_req_failed: ['rate<0.05'],  // 4xx를 실패로 카운트
}

// TO-BE
thresholds: {
  success_rate: ['rate>0.95'],  // 커스텀 메트릭으로 비즈니스 성공률 측정
}
```

### 교훈
- k6의 `http_req_failed`는 HTTP 관점의 실패 (4xx/5xx)
- 비즈니스 관점의 성공률은 커스텀 메트릭으로 분리해야 함
