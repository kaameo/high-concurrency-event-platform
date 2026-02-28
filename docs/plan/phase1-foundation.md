# Phase 1: 기반 구축 (1~2주)

## 1.1 프로젝트 초기화

### Spring Initializr 설정 (실제 프로젝트 기준)
- **Project:** Gradle - Kotlin DSL
- **Language:** Java 21
- **Spring Boot:** 4.0.3
- **Group:** `com.kaameo`
- **Artifact:** `event-platform`
- **Dependencies (초기 포함):**
  - Spring WebMVC (`spring-boot-starter-webmvc`)
  - Spring Data JPA
  - Spring for Apache Kafka
  - Spring Data Redis
  - PostgreSQL Driver
  - Validation
  - Lombok
  - Spring Boot Starter Test
  - Spring Batch
  - Spring Boot Actuator + Micrometer Prometheus (메트릭 노출)

### build.gradle.kts 추가 의존성
```kotlin
// QueryDSL
implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")

// Redisson (분산 락)
implementation("org.redisson:redisson-spring-boot-starter:3.27.0")

// Springdoc OpenAPI (Swagger)
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.4.0")

// Testcontainers
testImplementation("org.testcontainers:postgresql:1.19.7")
testImplementation("org.testcontainers:kafka:1.19.7")
```

### application.yml 구조
```yaml
spring:
  profiles:
    active: local

  datasource:
    url: jdbc:postgresql://localhost:5432/event_platform
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
    open-in-view: false

  data:
    redis:
      host: localhost
      port: 6379

  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
    consumer:
      group-id: coupon-issue-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"

  threads:
    virtual:
      enabled: true  # Java 21 Virtual Threads 활성화

server:
  port: 8080

springdoc:
  swagger-ui:
    path: /swagger-ui.html
```

---

## 1.2 인프라 환경 구성

### 환경변수 관리 (`.env.example`)
```dotenv
# === Database ===
POSTGRES_DB=event_platform
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
POSTGRES_PORT=5432

# === Redis ===
REDIS_PORT=6379
REDIS_MAX_MEMORY=256mb

# === Kafka ===
KAFKA_PORT=9092
KAFKA_NUM_PARTITIONS=1
KAFKA_UI_PORT=9091

# === Application ===
APP_PORT=8080
SPRING_PROFILES_ACTIVE=local

# === Monitoring ===
GRAFANA_ADMIN_PASSWORD=admin
PROMETHEUS_PORT=9090
GRAFANA_PORT=3000
```

> `.env.example`을 커밋하고, `.env`는 `.gitignore`에 포함 (이미 설정됨)

### Docker Compose (`docker-compose.yml`)
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports:
      - "${POSTGRES_PORT}:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "${REDIS_PORT}:6379"
    command: redis-server --maxmemory ${REDIS_MAX_MEMORY} --maxmemory-policy allkeys-lru

  kafka:
    image: apache/kafka:3.9.0
    ports:
      - "${KAFKA_PORT}:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_NUM_PARTITIONS: ${KAFKA_NUM_PARTITIONS}
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "${KAFKA_UI_PORT}:8080"
    depends_on:
      - kafka
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092

  # app:
  #   build: .
  #   ports:
  #     - "8080:8080"
  #   depends_on:
  #     - postgres
  #     - redis
  #     - kafka
  #   environment:
  #     SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/event_platform
  #     SPRING_DATASOURCE_USERNAME: postgres
  #     SPRING_DATASOURCE_PASSWORD: postgres
  #     SPRING_DATA_REDIS_HOST: redis
  #     SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092

  prometheus:
    image: prom/prometheus:latest
    ports:
      - "${PROMETHEUS_PORT}:9090"
    volumes:
      - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana:latest
    ports:
      - "${GRAFANA_PORT}:3000"
    depends_on:
      - prometheus
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}

volumes:
  postgres_data:
```

### Prometheus 설정 (`infra/prometheus/prometheus.yml`)
```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'spring-boot'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8080']

  - job_name: 'kafka'
    static_configs:
      - targets: ['kafka:9092']
```

### kind 클러스터 설정 (`infra/kind/config.yaml`)
```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 80
        hostPort: 80
        protocol: TCP
      - containerPort: 443
        hostPort: 443
        protocol: TCP
  - role: worker
    kubeadmConfigPatches:
      - |
        kind: JoinConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "node-role=infra"
  - role: worker
    kubeadmConfigPatches:
      - |
        kind: JoinConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "node-role=app"
```

### K8s 매니페스트 구조
```
infra/k8s/
├── namespace.yaml
├── app/
│   ├── deployment.yaml        # Spring Boot (nodeSelector: node-role=app)
│   ├── service.yaml
│   ├── hpa.yaml
│   └── ingress.yaml
├── kafka/
│   ├── statefulset.yaml       # nodeSelector: node-role=infra
│   ├── service.yaml
│   ├── pv.yaml
│   └── pvc.yaml
├── redis/
│   ├── statefulset.yaml       # nodeSelector: node-role=infra
│   ├── service.yaml
│   ├── pv.yaml
│   └── pvc.yaml
├── postgres/
│   ├── statefulset.yaml       # nodeSelector: node-role=infra
│   ├── service.yaml
│   ├── pv.yaml
│   └── pvc.yaml
└── monitoring/
    ├── prometheus-deployment.yaml
    ├── prometheus-configmap.yaml
    ├── grafana-deployment.yaml
    └── grafana-service.yaml
```

---

## 1.3 기본 API 구현

### API 스펙

#### `POST /api/v1/coupons/issue`
쿠폰 발급 요청 접수 (비동기)

**Headers:**
| Header | 필수 | 설명 |
|--------|------|------|
| `Idempotency-Key` | Yes | 클라이언트 생성 멱등성 키 (UUID 권장) |

**Request:**
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
    "requestId": "req-uuid-xxx",
    "status": "PENDING",
    "message": "발급 요청이 접수되었습니다."
  }
}
```

**Error Responses:**
| Status | Code | 설명 |
|--------|------|------|
| 400 | `INVALID_REQUEST` | 잘못된 요청 |
| 409 | `REJECTED_DUPLICATE` | 동일 Idempotency-Key 중복 요청 |
| 410 | `REJECTED_OUT_OF_STOCK` | 재고 소진 |
| 429 | `RATE_LIMIT_EXCEEDED` | 요청 제한 초과 |

#### `GET /api/v1/coupons/requests/{requestId}`
비동기 발급 요청 상태 조회

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "requestId": "req-uuid-xxx",
    "couponEventId": 1,
    "userId": 12345,
    "status": "ISSUED",
    "issuedAt": "2026-03-01T00:00:01"
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

#### `GET /api/v1/coupons/{couponEventId}`
쿠폰 이벤트 상세 조회

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "couponEventId": 1,
    "name": "신규 가입 쿠폰",
    "totalStock": 100000,
    "remainingStock": 45230,
    "status": "ACTIVE",
    "startAt": "2026-03-01T00:00:00",
    "endAt": "2026-03-01T23:59:59"
  }
}
```

### DB 스키마
```sql
CREATE TABLE coupon_event (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    total_stock INTEGER NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_at    TIMESTAMP NOT NULL,
    end_at      TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE coupon_issue (
    id               BIGSERIAL PRIMARY KEY,
    request_id       UUID NOT NULL UNIQUE,
    coupon_event_id  BIGINT NOT NULL REFERENCES coupon_event(id),
    user_id          BIGINT NOT NULL,
    idempotency_key  VARCHAR(64) NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    issued_at        TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (coupon_event_id, user_id),
    UNIQUE (idempotency_key)
);

CREATE INDEX idx_coupon_issue_request_id ON coupon_issue(request_id);
CREATE INDEX idx_coupon_issue_coupon_event_id ON coupon_issue(coupon_event_id);
CREATE INDEX idx_coupon_issue_user_id ON coupon_issue(user_id);
CREATE INDEX idx_coupon_issue_status ON coupon_issue(status);
```

### Enum 값
- **CouponEventStatus:** `ACTIVE`, `INACTIVE`, `EXPIRED`
- **IssueStatus:** `PENDING`, `ISSUED`, `REJECTED_OUT_OF_STOCK`, `REJECTED_DUPLICATE`, `FAILED`

### ApiResponse 공통 포맷
```java
public record ApiResponse<T>(
    boolean success,
    T data,
    String error,
    Meta meta
) {
    public record Meta(long total, int page, int limit) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null);
    }
}
```

### Phase 1 완료 기준
- [ ] `docker-compose up`으로 전체 인프라 기동
- [ ] Spring Boot 앱 정상 부팅 (DB, Redis, Kafka 연결)
- [ ] Swagger UI에서 API 확인 가능
- [ ] Prometheus → Grafana 메트릭 수집 확인
- [ ] 기본 CRUD 테스트 통과
