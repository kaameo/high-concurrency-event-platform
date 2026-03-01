# Phase 3 아키텍처 — Kubernetes 배포 + 성능 실험

## 시스템 아키텍처

```mermaid
graph TB
    subgraph "Client"
        K6[k6 Load Test<br/>500~10,000 VUs]
        UI[Test Console<br/>index.html]
    end

    subgraph "kind Cluster (Control Plane 1 + Worker 2)"
        subgraph "App Pods (HPA: 1~10)"
            POD1[event-platform Pod 1]
            POD2[event-platform Pod 2]
            PODN[event-platform Pod N]
        end

        HPA[HPA<br/>CPU 50%]

        subgraph "Kafka (StatefulSet)"
            KF[Kafka KRaft<br/>apache/kafka:3.9.0<br/>1 broker]
        end

        subgraph "Helm Charts"
            PG[(PostgreSQL<br/>Bitnami Helm)]
            RD[(Redis<br/>Bitnami Helm)]
        end

        subgraph "Monitoring (kube-prometheus-stack)"
            PROM[Prometheus]
            GRAF[Grafana]
        end
    end

    K6 -->|NodePort 30080| POD1
    K6 -->|NodePort 30080| POD2
    K6 -->|NodePort 30080| PODN
    UI -->|localhost:8080| POD1

    HPA -->|scale| POD1
    HPA -->|scale| POD2
    HPA -->|scale| PODN

    POD1 -->|DECR 원자적| RD
    POD1 -->|produce| KF
    POD2 -->|DECR 원자적| RD
    POD2 -->|produce| KF
    KF -->|consume| POD1
    KF -->|consume| POD2
    POD1 -->|INSERT| PG
    POD2 -->|INSERT| PG

    PROM -->|scrape| POD1
    PROM -->|scrape| POD2
    PROM -->|scrape| KF
    PROM -->|scrape| RD
    GRAF -->|query| PROM

    style RD fill:#DC382D,color:#fff
    style PG fill:#336791,color:#fff
    style KF fill:#231F20,color:#fff
    style HPA fill:#326CE5,color:#fff
    style PROM fill:#E6522C,color:#fff
    style GRAF fill:#F46800,color:#fff
    style K6 fill:#7D64FF,color:#fff
```

## kind 클러스터 구성

```mermaid
graph TB
    subgraph "kind Cluster: event-platform"
        CP[Control Plane<br/>Port 30080 → App<br/>Port 30300 → Grafana]
        W1[Worker 1]
        W2[Worker 2]
    end

    subgraph "배포 구성"
        D1[event-platform<br/>Deployment + HPA]
        D2[Kafka<br/>StatefulSet + PVC]
        D3[PostgreSQL<br/>Helm Chart]
        D4[Redis<br/>Helm Chart]
        D5[kube-prometheus-stack<br/>Helm Chart]
    end

    CP --> D1
    W1 --> D1
    W1 --> D2
    W2 --> D1
    W2 --> D3
    W2 --> D4
    CP --> D5

    style CP fill:#326CE5,color:#fff
    style W1 fill:#326CE5,color:#fff
    style W2 fill:#326CE5,color:#fff
```

## Kubernetes 매니페스트 구조

```
k8s/
├── kind-config.yaml              # kind 클러스터 (CP 1 + Worker 2)
├── run-experiment-b.sh           # 실험 B 자동화 스크립트
├── app/
│   ├── deployment.yaml           # Deployment (replicas: 1, HPA 관리)
│   ├── service.yaml              # NodePort 30080
│   ├── configmap.yaml            # 환경변수 (DB/Redis/Kafka 연결)
│   ├── hpa-cpu.yaml              # CPU 50% 기반 HPA (채택)
│   ├── hpa-memory.yaml           # Memory 70% 기반 HPA (실험용)
│   └── hpa-custom.yaml           # Custom Metric HPA (실험용)
├── kafka/
│   └── kafka.yaml                # StatefulSet + Headless Service + PVC
├── values/
│   ├── postgresql-values.yaml    # Bitnami PostgreSQL Helm values
│   ├── redis-values.yaml         # Bitnami Redis Helm values
│   └── kafka-values.yaml         # Kafka Helm values
└── docs/
    └── access-urls.md            # 클러스터 접속 URL 정리
```

## 실험 A: DB 직결 vs Kafka 비동기

```mermaid
graph LR
    subgraph "A-1: Kafka 비동기"
        REQ1[Request] -->|DECR| RS1[(Redis)]
        REQ1 -->|produce| KF1[Kafka]
        KF1 -->|consume| DB1[(DB)]
        REQ1 -->|202 Accepted| RES1[Response<br/>avg 12ms]
    end

    subgraph "A-2: DB 직결 동기"
        REQ2[Request] -->|DECR| RS2[(Redis)]
        REQ2 -->|INSERT 동기| DB2[(DB)]
        REQ2 -->|200 OK| RES2[Response<br/>avg 67ms]
    end

    style RS1 fill:#27ae60,color:#fff
    style KF1 fill:#27ae60,color:#fff
    style RS2 fill:#f39c12,color:#fff
    style DB2 fill:#e74c3c,color:#fff
```

### 결과 (500 VU)

| 항목 | Async (Kafka) | Sync (DB) | 비율 |
|------|--------------|-----------|------|
| **RPS** | **4,711/s** | 3,019/s | **1.56x** |
| **p50 Latency** | **12.09ms** | 66.75ms | **5.5x 빠름** |
| **p95 Latency** | **46.98ms** | 96.19ms | **2.0x 빠름** |
| 정합성 | 100,000 | 100,000 | 동일 |

**결론**: Kafka 비동기 방식 채택 (ADR-007)

## 실험 B: HPA Auto-scaling 전략

```mermaid
graph TD
    SPIKE[Spike Traffic<br/>0 → 10,000 VU] --> HPA

    HPA -->|CPU 50%| B1[B-1: CPU 기반<br/>최대 8 Pod]
    HPA -->|Memory 70%| B2[B-2: Memory 기반<br/>최대 10 Pod]
    HPA -->|CPU 40% + Mem 60%| B3[B-3: 복합<br/>최대 10 Pod]

    B1 -->|RPS 1,329<br/>성공률 76.76%| R1[가장 안정적]
    B2 -->|RPS 1,169<br/>OOM Kill 다수| R2[불안정]
    B3 -->|RPS 695<br/>CrashLoopBackOff| R3[실패]

    style R1 fill:#27ae60,color:#fff
    style R2 fill:#f39c12,color:#fff
    style R3 fill:#e74c3c,color:#fff
    style SPIKE fill:#7D64FF,color:#fff
```

### 결과

| 구성 | 최대 Pod | RPS | p95 Latency | 성공률 | 안정성 |
|------|---------|-----|-------------|--------|--------|
| **B-1 CPU 50%** | 8 | **1,329** | 24.42s | **76.76%** | 재시작 없음 |
| B-2 Memory 70% | 10 | 1,169 | 59.99s | 72.36% | OOM Kill |
| B-3 복합 | 10 | 695 | 54.47s | 25.99% | CrashLoop |

**결론**: CPU 기반 HPA 채택 (ADR-008)

## 실험 C: Kafka 파티션 튜닝

```mermaid
graph LR
    subgraph "C-1: 1 파티션 / 1 컨슈머"
        P1[Partition 0] --> C1[Consumer 1]
    end

    subgraph "C-2: 3 파티션 / 3 컨슈머"
        P2a[Partition 0] --> C2a[Consumer 1]
        P2b[Partition 1] --> C2b[Consumer 2]
        P2c[Partition 2] --> C2c[Consumer 3]
    end

    subgraph "C-3: 10 파티션 / 10 컨슈머"
        P3[Partition 0~9] --> C3[Consumer 1~10]
    end

    style P1 fill:#27ae60,color:#fff
    style P2a fill:#f39c12,color:#fff
    style P3 fill:#e74c3c,color:#fff
```

### 결과 (1,000 VU Spike)

| 지표 | C-1 (1/1) | C-2 (3/3) | C-3 (10/10) |
|------|-----------|-----------|-------------|
| **RPS** | **6,221** | 5,523 | 5,363 |
| p95 Latency | **118.46ms** | 168.88ms | 140.76ms |
| DB 발급 건수 | 100,000 | 100,000 | 100,000 |

**결론**: 단일 브로커 환경에서는 파티션 증가가 오히려 오버헤드. 멀티 브로커 환경에서 재검증 필요.

## Phase 1 → 2 → 3 아키텍처 진화

```mermaid
graph TD
    subgraph "Phase 1 — 동기 DB 직결"
        P1A[API] -->|COUNT + CHECK + INSERT| P1D[(PostgreSQL)]
        P1A -->|200 OK| P1R[Response]
        P1P[문제: Race Condition<br/>초과 발급 4건]
    end

    subgraph "Phase 2 — Redis + Kafka 비동기"
        P2A[API] -->|DECR 원자적| P2RD[(Redis)]
        P2A -->|produce| P2K[Kafka]
        P2A -->|202 Accepted| P2R[Response]
        P2K -->|consume + INSERT| P2D[(PostgreSQL)]
        P2S[해결: 정합성 100%<br/>멱등성 + Rate Limit]
    end

    subgraph "Phase 3 — Kubernetes + 성능 실험"
        P3A[HPA Auto-scaling<br/>1~10 Pods] -->|DECR| P3RD[(Redis)]
        P3A -->|produce| P3K[Kafka]
        P3K -->|consume| P3D[(PostgreSQL)]
        P3M[Prometheus + Grafana<br/>실시간 모니터링]
        P3E[k6 부하 테스트<br/>500~10,000 VU]
        P3R[검증: CPU HPA 채택<br/>Kafka 비동기 1.56x 처리량]
    end

    P1P -->|Phase 2에서 해결| P2S
    P2S -->|Phase 3에서 검증| P3R

    style P1P fill:#e74c3c,color:#fff
    style P2S fill:#27ae60,color:#fff
    style P3R fill:#326CE5,color:#fff
    style P3M fill:#E6522C,color:#fff
```

## 모니터링 스택

```mermaid
graph LR
    subgraph "Metrics Sources"
        APP[Spring Boot<br/>/actuator/prometheus]
        RE[Redis Exporter<br/>:9121]
        KJE[Kafka JMX Exporter<br/>:5556]
        KE[Kafka Exporter<br/>:9308]
    end

    subgraph "Collection"
        PROM[Prometheus<br/>15s scrape]
    end

    subgraph "Visualization"
        GRAF[Grafana<br/>19 panels]
    end

    APP --> PROM
    RE --> PROM
    KJE --> PROM
    KE --> PROM
    PROM --> GRAF

    subgraph "Dashboard Panels"
        D1[App: CPU, Memory, Threads, Connections]
        D2[HTTP: Success Rate, p95/p99, RPS]
        D3[Coupon: Issue Rate, Rejected, Total]
        D4[Kafka: Consumer Lag, Messages, Latency]
        D5[Redis: Clients, Memory, Ops/sec]
    end

    GRAF --> D1
    GRAF --> D2
    GRAF --> D3
    GRAF --> D4
    GRAF --> D5

    style PROM fill:#E6522C,color:#fff
    style GRAF fill:#F46800,color:#fff
    style APP fill:#6DB33F,color:#fff
    style RE fill:#DC382D,color:#fff
    style KJE fill:#231F20,color:#fff
    style KE fill:#231F20,color:#fff
```

## App Deployment 상세

```yaml
# 리소스 제한
resources:
  requests: { cpu: 250m, memory: 512Mi }
  limits:   { cpu: 1000m, memory: 1Gi }

# Probes
readinessProbe: /actuator/health (30s 후, 10s 주기)
livenessProbe:  /actuator/health (60s 후, 15s 주기)

# HPA (채택: CPU 기반)
minReplicas: 1
maxReplicas: 10
targetCPUUtilization: 50%
```

## 병목 지점 분석

```mermaid
graph TD
    subgraph "식별된 병목"
        BN1[실험 A: HikariCP 풀 경합<br/>DB 동기 방식에서 커넥션 부족]
        BN2[실험 B: JVM GC ↔ Memory HPA 괴리<br/>GC 후 메모리 급감 → HPA 혼란]
        BN3[실험 C: 단일 브로커 파티션 한계<br/>I/O 병목이 브로커 레벨에서 발생]
    end

    subgraph "해결 방향"
        S1[Kafka 비동기로 DB 부하 분리]
        S2[CPU 기반 HPA로 예측 가능한 스케일링]
        S3[멀티 브로커 환경에서 파티션 병렬화]
    end

    BN1 --> S1
    BN2 --> S2
    BN3 --> S3

    style BN1 fill:#e74c3c,color:#fff
    style BN2 fill:#e74c3c,color:#fff
    style BN3 fill:#e74c3c,color:#fff
    style S1 fill:#27ae60,color:#fff
    style S2 fill:#27ae60,color:#fff
    style S3 fill:#f39c12,color:#fff
```

## 성능 요약 (Phase 1 → 2 → 3)

| 지표 | Phase 1 | Phase 2 | Phase 3 (Async) |
|------|---------|---------|-----------------|
| 평균 RPS | ~530 | 543 | **4,711** |
| p50 Latency | — | 5.44ms | **12.09ms** |
| p95 Latency | — | 12.04ms | **46.98ms** |
| 정합성 | 100,004 (초과 4건) | **100,000** | **100,000** |
| 스케일링 | 단일 프로세스 | 단일 프로세스 | **HPA 1~10 Pods** |
| 모니터링 | 없음 | Prometheus 기본 | **19 panel 대시보드** |
