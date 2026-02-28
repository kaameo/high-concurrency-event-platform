# PRD: 분산 환경에서의 선착순 이벤트 플랫폼

> High-concurrency event platform designed for 100k+ TPS, featuring Event-Driven Architecture with Java Spring Boot, Kafka, and Kubernetes.

## 1. 프로젝트 개요

### 목적
"기능 구현"이 아닌 **"한계 돌파"**를 목표로, 초당 **100,000건(100k TPS)** 수준의 동시 요청을 인프라 레벨에서 견디고 검증한다.

### 핵심 시나리오
**"10만 장의 한정판 쿠폰을 100만 명에게 선착순 발급한다."**

### 성공 기준 (정량 KPI)
| 기준 | 설명 |
|------|------|
| **Data Consistency** | 최종 발급 수는 정확히 100,000건 (초과 발급 0건) |
| **Throughput** | 목표 부하 테스트에서 피크 100k TPS 달성 |
| **Availability** | 목표 부하 구간에서 성공률 99.9% 이상 |
| **Latency** | 목표 부하 구간에서 API p95 ≤ 200ms, p99 ≤ 500ms |
| **Durability** | 노드 장애 시에도 발급 데이터 유실 0건 |

---

## 2. 데이터 흐름 (Core Flow)

```
User Request (100만 건)
    ↓
[1] Rate Limiter (Redis) — 초당 요청 수 제어, 백엔드 보호
    ↓
[2] Stock Validation (Redis DECR) — DB 없이 즉시 재고 차감
    ↓
[3] Enqueue (Kafka Producer) — 검증된 요청만 Topic 발행, 202 + requestId 반환
    ↓
[4] Processor (Kafka Consumer) — 메시지 소비 후 DB 영구 저장
    ↓
[5] Result — requestId 기반 최종 발급 상태 조회
```

### 비동기 API 계약
| API | 목적 | 응답 |
|-----|------|------|
| `POST /api/v1/coupons/issue` | 발급 요청 접수 | `202 Accepted`, `requestId`, `status=PENDING` |
| `GET /api/v1/coupons/requests/{requestId}` | 비동기 처리 결과 확인 | `PENDING \| ISSUED \| REJECTED_OUT_OF_STOCK \| REJECTED_DUPLICATE \| FAILED` |

### 요청/이벤트 식별자 계약
- 클라이언트는 `Idempotency-Key` 헤더를 필수로 전달한다.
- 서버는 `requestId`를 생성해 응답/이벤트/로그 추적 키로 사용한다.
- Kafka 메시지 키는 `requestId`를 사용해 중복 처리 판별과 추적성을 보장한다.

---

## 3. 기술 스택

### Backend Core
| 항목 | 기술 | 비고 |
|------|------|------|
| Language | **Java 21** | Virtual Threads 기반 대규모 동시 처리 |
| Framework | **Spring Boot 4.0.3** | API 및 비동기 처리 |
| ORM | **Spring Data JPA** | 영구 저장 계층 |
| Query | **QueryDSL** | 복잡한 조회 쿼리 |
| Batch | **Spring Batch** | 정산/집계 보조 경로 |

### Data & Messaging
| 항목 | 기술 | 비고 |
|------|------|------|
| Main DB | **PostgreSQL** | 트랜잭션/JSONB |
| Cache & Lock | **Redis + Redisson** | Atomic Counter + 분산 락 |
| Message Broker | **Apache Kafka** | 요청 버퍼링, 비동기 처리 |

### Infrastructure & DevOps
| 항목 | 기술 | 비고 |
|------|------|------|
| Container | **Docker** | 개발/실험 환경 통일 |
| Orchestration | **Kubernetes (kind)** | control-plane 1 + worker 2 |
| CI/CD | **GitHub Actions** | 보류 — 핵심 기능 완료 후 도입 |

### Observability & Test
| 항목 | 기술 | 비고 |
|------|------|------|
| Monitoring | **Prometheus + Grafana** | 시스템/Kafka 지표 시각화 |
| Load Testing | **k6 + Locust** | k6 메인, Locust 보조 비교 |
| API Docs | **Springdoc-openapi (Swagger)** | API 문서/테스트 |

---

## 4. 신뢰성 요구사항 (Reliability Contracts)

### Idempotency/중복 방지
- 동일 `Idempotency-Key`는 동일 요청으로 처리한다.
- 최초 성공 요청만 `ISSUED`, 이후 동일 키 요청은 `REJECTED_DUPLICATE`로 귀결한다.

### Kafka 이벤트 스키마 (문서 레벨)
- Message Key: `requestId`
- 필수 필드: `requestId`, `userId`, `couponEventId`, `issuedAt`, `idempotencyKey`, `traceId`

### 실패/재처리 정책
- DLQ 토픽 명명 규칙: `<domain>.<event>.dlq`
- 최대 재시도 횟수: 3회 (지수 백오프)
- 재시도 초과 시 DLQ 적재 및 운영자 재처리 절차 수행

### 순서/정합성 기준
- 사용자 단위 발급 이벤트는 동일 파티션 라우팅(파티션 키: `userId`)을 기본값으로 한다.
- 최종 정합성 보장을 위해 DB 적재 시 `requestId` 유니크 제약을 둔다.

---

## 5. K8s 클러스터 구성 (kind)

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
            node-labels: ingress-ready=true
    extraPortMappings:
      - containerPort: 80
        hostPort: 80
        protocol: TCP
      - containerPort: 443
        hostPort: 443
        protocol: TCP
  - role: worker
  - role: worker
```

- NodeSelector로 인프라/애플리케이션 워크로드 역할 분리
- PV/PVC로 Kafka/Redis 데이터 영속성 확보

---

## 6. 실험 (Experiments)

### 실험 A: DB 직결 vs Kafka 버퍼링 성능 비교
- **측정:** 응답 시간(Latency), 처리량(Throughput), 사용자 체감 응답 시간
- **기대 결과:** Kafka 도입 시 동시성 구간에서 API 응답 시간 유의미 개선, Eventual Consistency 지연 수치화

### 실험 B: K8s HPA Auto-scaling 임계치 최적화
- **측정:** CPU vs Memory vs Request 기반 스케일링 비교, 스케일 반응 시간
- **기대 결과:** 복합 지표 조합으로 최적 스케일업 시점 도출

### 실험 C: Kafka 파티션 및 Consumer Group 튜닝
- **측정:** 파티션 1/10/50개 + Consumer Group 조합별 처리량, lag, 실패율
- **단계형 목표:** 10k TPS → 50k TPS → 100k TPS
- **기대 결과:** 파티션 수와 Consumer 수 최적 매칭으로 100k TPS 구간에서 lag 안정화

---

## 7. 산출물 (Deliverables)

| 산출물 | 설명 |
|--------|------|
| **성능 리포트** | KPI 달성 여부, 병목 원인/해결 기록 |
| **Grafana 대시보드** | CPU, Memory, Kafka Lag, 처리량 시각 증거 |
| **Troubleshooting 기록** | 장애 발견/대응/재발 방지 기록 |
| **ADR** | 기술적 의사결정 기록 (Issues/Discussions) |
| **README** | 아키텍처, 실행 방법, 벤치마크 결과 |

---

## 8. 정식 범위 (Phase 4: 장애 복구 실험)

Phase 1~3과 동일한 정식 범위로 수행:
- Worker Node 장애 시뮬레이션 (`docker stop`)
- Pod 재배치 관찰 (Eviction & Scheduling)
- 데이터 유실 여부 검증 (PV/PVC)
- 장애 복구 시간(MTTR) 측정
- Pod Anti-Affinity 설정 실험
- 부하 중 장애 유발 시 V자형 회복 곡선 획득
