# Phase 4 아키텍처 — 장애 복구 실험 (Chaos Engineering)

## 시스템 아키텍처

```mermaid
graph TB
    subgraph "Client"
        K6[k6 Load Test<br/>3,000 VUs]
    end

    subgraph "kind Cluster (Control Plane 1 + Worker 2)"
        subgraph "Worker 1: event-platform-worker"
            POD[event-platform Pod<br/>Spring Boot]
            RD[(Redis<br/>Bitnami Helm)]
            AM[Alertmanager]
            PO[Prometheus Operator]
        end

        subgraph "Worker 2: event-platform-worker2"
            KF[Kafka KRaft<br/>1 broker]
            PG[(PostgreSQL<br/>Bitnami Helm)]
            GRAF[Grafana]
            PROM[Prometheus]
        end

        subgraph "Control Plane"
            CP[K8s Master<br/>+ Ingress]
        end
    end

    subgraph "Chaos Injection"
        CHAOS1[D-1: docker stop worker2<br/>Infra 노드 장애]
        CHAOS2[D-2: docker stop worker<br/>App 노드 장애]
    end

    K6 -->|NodePort 30080| POD
    POD -->|DECR 원자적| RD
    POD -->|produce| KF
    KF -->|consume| POD
    POD -->|INSERT| PG

    PROM -->|scrape| POD
    PROM -->|scrape| KF
    PROM -->|scrape| RD
    GRAF -->|query| PROM
    AM -->|alert| PO

    CHAOS1 -.->|stop/start| KF
    CHAOS1 -.->|stop/start| PG
    CHAOS2 -.->|stop/start| POD
    CHAOS2 -.->|stop/start| RD

    style RD fill:#DC382D,color:#fff
    style PG fill:#336791,color:#fff
    style KF fill:#231F20,color:#fff
    style PROM fill:#E6522C,color:#fff
    style GRAF fill:#F46800,color:#fff
    style K6 fill:#7D64FF,color:#fff
    style CHAOS1 fill:#e74c3c,color:#fff
    style CHAOS2 fill:#e74c3c,color:#fff
    style CP fill:#326CE5,color:#fff
    style AM fill:#e74c3c,color:#fff
```

## kind 클러스터 구성 (노드 역할 분리)

```mermaid
graph TB
    subgraph "kind Cluster: event-platform"
        CP[Control Plane<br/>Port 30080 → App<br/>Port 30300 → Grafana]
        W1[Worker 1<br/>event-platform-worker]
        W2[Worker 2<br/>event-platform-worker2]
    end

    subgraph "Worker 1 배포"
        A1[App Pod — Spring Boot]
        A2[Redis — Bitnami Helm]
        A3[Alertmanager]
        A4[Prometheus Operator]
    end

    subgraph "Worker 2 배포"
        B1[Kafka — StatefulSet + PVC]
        B2[PostgreSQL — Bitnami Helm]
        B3[Grafana]
        B4[Prometheus]
    end

    W1 --> A1
    W1 --> A2
    W1 --> A3
    W1 --> A4
    W2 --> B1
    W2 --> B2
    W2 --> B3
    W2 --> B4

    style CP fill:#326CE5,color:#fff
    style W1 fill:#326CE5,color:#fff
    style W2 fill:#326CE5,color:#fff
    style A1 fill:#6DB33F,color:#fff
    style B1 fill:#231F20,color:#fff
```

## 파일 구조

```
k8s/
├── kind-config.yaml              # kind 클러스터 (CP 1 + Worker 2)
├── run-experiment-d.sh           # 장애 복구 실험 자동화 스크립트
├── app/
│   ├── deployment.yaml           # Probe 분리 (liveness/readiness)
│   ├── service.yaml              # NodePort 30080
│   ├── configmap.yaml            # 환경변수 (DB/Redis/Kafka 연결)
│   └── hpa-cpu.yaml              # CPU 50% 기반 HPA
├── kafka/
│   └── kafka.yaml                # StatefulSet + PVC (데이터 영속성)
└── values/
    ├── postgresql-values.yaml    # PVC 영속성 설정
    └── redis-values.yaml         # Bitnami Redis Helm values

k6/
└── phase4-resilience.js          # 3,000 VU 장애 복구 부하 테스트

docs/adr/
└── 009-resilience-recovery-strategy.md  # Probe 분리 + 재연결 전략
```

## 실험 D-1: Infra 노드 장애 (Worker 2 — Kafka/PostgreSQL)

```mermaid
sequenceDiagram
    participant K6 as k6 (3,000 VU)
    participant App as App Pod (Worker 1)
    participant Redis as Redis (Worker 1)
    participant Kafka as Kafka (Worker 2)
    participant PG as PostgreSQL (Worker 2)

    Note over K6,PG: 정상 트래픽 (T=0)
    K6->>App: POST /api/v1/coupons/issue
    App->>Redis: DECR (원자적 재고 차감)
    App->>Kafka: produce
    Kafka->>PG: consume → INSERT

    Note over Kafka,PG: docker stop worker2 (T=2min)
    K6->>App: 요청 지속
    App->>Redis: DECR ✅ (Worker 1 정상)
    App--xKafka: produce ❌ (Kafka 불가)
    App-->>K6: 5xx / Timeout (30s)

    Note over Kafka,PG: docker start worker2 (T=5min)
    Kafka->>PG: Pod 재시작 → 연결 복구
    App->>Kafka: produce 재개
    Note over K6,PG: MTTR > 10분 ❌
```

### 결과 (초기 → Probe 개선 후)

| 지표 | 초기 | Probe 개선 후 |
|------|------|-------------|
| 총 요청 수 | 134,261 | 122,272 |
| 평균 RPS | 209 req/s | — |
| 쿠폰 발급 성공 | 9,861건 | 7,592건 |
| 에러 (5xx/timeout) | 110,067건 | — |
| 성공률 | 18.02% | 6.21% |
| Detection Time | 52s ✅ | 56s ✅ |
| App Pod 재시작 | — | 7회 (변화 없음) |
| Total MTTR | > 10분 ❌ | > 10분 ❌ |

### MTTR 타임라인 (Probe 개선 후)

```
T_STOP      : 17:56:13  ── docker stop worker2
T_NOTREADY  : 17:57:09  ── +56s (노드 NotReady 감지)
T_RECOVER   : 18:00:09  ── docker start worker2
T_NODE_READY: 18:00:15  ── +6s (노드 Ready)
T_PODS_READY: 18:00:25  ── +10s (Pod Ready)
T_END       : 18:04:53  ── 테스트 종료 (완전 복구 미완)
```

**결론**: Kafka/PostgreSQL SPOF로 인해 MTTR > 10분. 단일 브로커 장애 시 전체 쓰기 경로 차단.

## 실험 D-2: App 노드 장애 (Worker 1 — App/Redis)

```mermaid
sequenceDiagram
    participant K6 as k6 (3,000 VU)
    participant App as App Pod (Worker 1)
    participant Redis as Redis (Worker 1)
    participant Kafka as Kafka (Worker 2)
    participant PG as PostgreSQL (Worker 2)

    Note over K6,PG: 정상 트래픽 (T=0)
    K6->>App: POST /api/v1/coupons/issue
    App->>Redis: DECR
    App->>Kafka: produce

    Note over App,Redis: docker stop worker (T=2min)
    K6--xApp: 연결 불가 ❌
    Note over K6: 전체 서비스 중단

    Note over App,Redis: docker start worker (T=5min)
    App->>Redis: Pod 재시작 → 즉시 복구
    App->>Kafka: produce 재개
    Note over K6,PG: MTTR > 10분 ❌
```

### 결과 (초기 → Probe 개선 후)

| 지표 | 초기 | Probe 개선 후 |
|------|------|-------------|
| 총 요청 수 | 146,841 | 129,884 |
| 평균 RPS | 229 req/s | — |
| 쿠폰 발급 성공 | 9,854건 | 15,298건 |
| 에러 (5xx/timeout) | 111,053건 | — |
| 성공률 | 24.37% | 11.78% |
| Detection Time | 46s ✅ | 47s ✅ |
| App Pod 재시작 | 5회 | 3회 (40% 감소 ✅) |
| Total MTTR | > 10분 ❌ | > 10분 ❌ |

### MTTR 타임라인 (Probe 개선 후)

```
T_STOP      : 18:10:47  ── docker stop worker
T_NOTREADY  : 18:11:34  ── +47s (노드 NotReady 감지)
T_RECOVER   : 18:14:34  ── docker start worker
T_NODE_READY: 18:14:39  ── +5s (노드 Ready)
T_PODS_READY: 18:14:39  ── 즉시 (0s, Pod 즉시 Ready)
T_END       : 18:19:18  ── 테스트 종료
```

**결론**: 단일 레플리카로 인해 전체 서비스 중단. Pod 재시작은 40% 감소했으나 MTTR 목표 미달.

## Phase 3 → 4 아키텍처 진화

```mermaid
graph TD
    subgraph "Phase 3 — Kubernetes + 성능 최적화"
        P3A[HPA Auto-scaling<br/>1~10 Pods] -->|DECR| P3RD[(Redis)]
        P3A -->|produce| P3K[Kafka]
        P3K -->|consume| P3D[(PostgreSQL)]
        P3M[Prometheus + Grafana<br/>실시간 모니터링]
        P3R[검증: CPU HPA 채택<br/>Kafka 비동기 1.56x 처리량]
    end

    subgraph "Phase 4 — 장애 복구 실험 (Chaos Engineering)"
        P4A[App Pod<br/>Probe 분리] -->|DECR| P4RD[(Redis)]
        P4A -->|produce| P4K[Kafka<br/>PVC 영속성]
        P4K -->|consume| P4D[(PostgreSQL<br/>PVC 영속성)]
        P4C[Chaos Injection<br/>노드 장애 시뮬레이션]
        P4M[MTTR 측정<br/>Detection ≈ 50s ✅]
        P4I[개선 과제 도출<br/>Circuit Breaker, Multi-replica]
    end

    P3R -->|Phase 4에서 검증| P4C
    P4C --> P4M
    P4M --> P4I

    style P3R fill:#326CE5,color:#fff
    style P3M fill:#E6522C,color:#fff
    style P4C fill:#e74c3c,color:#fff
    style P4M fill:#f39c12,color:#fff
    style P4I fill:#27ae60,color:#fff
```

## Probe 전략 (ADR-009 적용)

```yaml
# Phase 3: 단일 Probe
readinessProbe: /actuator/health (30s 후, 10s 주기)
livenessProbe:  /actuator/health (60s 후, 15s 주기)

# Phase 4: Probe 분리 (ADR-009)
readinessProbe:
  path: /actuator/health/readiness    # 트래픽 수신 가능 여부
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 6

livenessProbe:
  path: /actuator/health/liveness     # 프로세스 생존 여부
  initialDelaySeconds: 90             # 60s → 90s (기동 시간 확보)
  periodSeconds: 15
  failureThreshold: 6                 # 3 → 6 (불필요한 재시작 방지)
```

## 재연결 설정 (Spring Boot)

```yaml
# HikariCP (PostgreSQL)
spring.datasource.hikari:
  connection-test-query: SELECT 1
  validation-timeout: 3000
  connection-timeout: 5000

# Lettuce (Redis)
spring.data.redis.lettuce:
  shutdown-timeout: 200ms

# Kafka Consumer
spring.kafka.consumer.properties:
  reconnect.backoff.ms: 1000
  reconnect.backoff.max.ms: 10000
```

## 병목 지점 분석

```mermaid
graph TD
    subgraph "식별된 SPOF"
        BN1[Kafka 단일 브로커<br/>Worker 2 장애 시 쓰기 경로 차단]
        BN2[App 단일 레플리카<br/>Worker 1 장애 시 전체 서비스 중단]
        BN3[Graceful Degradation 부재<br/>30s timeout 대기 → 응답 지연]
    end

    subgraph "Phase 4-A 개선 (즉시 적용)"
        S1[Circuit Breaker — Resilience4j<br/>p95 30s → 3s]
        S2[App replicas: 2 + Anti-Affinity<br/>D-2 서비스 중단 방지]
        S3[HikariCP keepalive + max-lifetime<br/>stale 연결 조기 감지]
        S4[Kafka backoff 단축<br/>재연결 50% 단축]
        S5[PodDisruptionBudget<br/>드레인 가용성 보장]
    end

    subgraph "Phase 4-B 개선 (인프라 확장)"
        S6[Kafka 다중 브로커 3대]
        S7[PostgreSQL HA — Patroni]
        S8[Redis Sentinel/Cluster]
    end

    BN1 --> S1
    BN1 --> S6
    BN2 --> S2
    BN2 --> S5
    BN3 --> S1
    BN3 --> S3
    BN3 --> S4

    style BN1 fill:#e74c3c,color:#fff
    style BN2 fill:#e74c3c,color:#fff
    style BN3 fill:#e74c3c,color:#fff
    style S1 fill:#27ae60,color:#fff
    style S2 fill:#27ae60,color:#fff
    style S3 fill:#27ae60,color:#fff
    style S4 fill:#27ae60,color:#fff
    style S5 fill:#27ae60,color:#fff
    style S6 fill:#f39c12,color:#fff
    style S7 fill:#f39c12,color:#fff
    style S8 fill:#f39c12,color:#fff
```

## 예상 MTTR 개선

| 단계 | MTTR | 주요 변경 |
|------|------|----------|
| **현재 (Phase 4)** | > 10분 ❌ | 단일 레플리카, SPOF 다수 |
| **Phase 4-A 적용 후** | 3~5분 | Circuit Breaker, Multi-replica, PDB |
| **Phase 4-A + 4-B** | < 2분 ✅ | Kafka 3 broker, PostgreSQL HA, Redis HA |

## 성능 요약 (Phase 1 → 2 → 3 → 4)

| 지표 | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|---------|
| 평균 RPS | ~530 | 543 | **4,711** | 209~229 (장애 중) |
| p95 Latency | — | 12.04ms | 46.98ms | 29.99s (장애 중) |
| 정합성 | 100,004 (초과 4건) | 100,000 | 100,000 | 100,000 |
| 스케일링 | 단일 프로세스 | 단일 프로세스 | HPA 1~10 Pods | HPA + Probe 분리 |
| 장애 감지 | 없음 | 없음 | 없음 | **≈ 50s (Detection)** |
| MTTR | 측정 없음 | 측정 없음 | 측정 없음 | **> 10분 (개선 필요)** |
| 모니터링 | 없음 | Prometheus 기본 | 19 panel 대시보드 | + Alertmanager |
