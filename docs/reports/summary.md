# 종합 성능 리포트

> 선착순 쿠폰 발급 시스템 — Phase 1~4 실험 결과 종합 분석

## 실험 개요

| 실험 | Phase | 목적 | 환경 |
|------|-------|------|------|
| A | 3 | DB 직결 vs Kafka 비동기 성능 비교 | Docker Compose (500 VU) |
| B | 3 | HPA Auto-scaling 메트릭별 최적화 | kind 클러스터 (10,000 VU) |
| C | 3 | Kafka 파티션 · Consumer 병렬화 | Docker Compose (1,000 VU) |
| D | 4 | 노드 장애 복구 및 MTTR 측정 | kind 클러스터 (3,000 VU) |

---

## 실험 A: DB 직결 vs Kafka 비동기

> **결론: Kafka 비동기가 전 지표에서 우수 (RPS 1.56x, p50 5.5x)**

### 조건
- 500 VU Spike 패턴 (2분 30초)
- 재고 100,000장
- 동일 Redis 재고 차감 + 멱등성 + Rate Limit 파이프라인

### 결과

| 지표 | Async (Kafka) | Sync (DB) | 비율 |
|------|--------------|-----------|------|
| **RPS (avg)** | **4,711/s** | 3,019/s | **1.56x** |
| p50 Latency | **12.09ms** | 66.75ms | 5.5x 빠름 |
| p95 Latency | **46.98ms** | 96.19ms | 2.0x 빠름 |
| Max Latency | **86.25ms** | 359.27ms | 4.2x 빠름 |
| 성공률 | 100% | 100% | 동일 |
| 재고 정합성 | 정확 (100,000건) | 정확 (100,000건) | 동일 |

### 핵심 인사이트

1. **병목은 HikariCP 커넥션 풀 경합** — 500 VU가 10개 커넥션을 경합하여 Sync 방식에서 대기 시간 급증
2. **Async는 DB 커넥션을 사용하지 않음** — Kafka produce 후 즉시 스레드 해제 → 더 많은 동시 요청 처리
3. **Max latency 안정성** — Async 86ms vs Sync 359ms로 피크 시 4.2배 차이
4. **아키텍처적 이점** — API/DB 계층 분리, Consumer 독립 스케일링, DB 장애 시에도 API 응답 가능

> 관련 결정: [ADR-007 Kafka 비동기 방식 성능 우위 실증](../adr/007-async-over-sync-experiment.md)

---

## 실험 B: HPA Auto-scaling 최적화

> **결론: CPU 기반 HPA(50%)가 가장 안정적이고 예측 가능**

### 조건
- kind 클러스터 (Control Plane 1 + Worker 2)
- 10,000 VU Spike (30초 ramp, 5분 유지)
- minReplicas 1 / maxReplicas 10

### 결과

| 구성 | 메트릭 | 최대 Pod | RPS | p95 Latency | 성공률 | 특이사항 |
|------|--------|----------|-----|-------------|--------|----------|
| **B-1** | **CPU 50%** | 8 | **1,329** | **24.42s** | **76.76%** | 가장 안정적 |
| B-2 | Memory 70% | 10 | 1,169 | 59.99s | 72.36% | OOM Kill 발생 |
| B-3 | CPU 40%+Mem 60% | 10 | 695 | 54.47s | 25.99% | CrashLoopBackOff |

### 핵심 인사이트

1. **CPU 메트릭이 요청 처리량에 가장 직접적으로 비례** — 예측 가능한 스케일링
2. **Memory 기반은 JVM에서 비권장** — GC 전까지 메모리를 반환하지 않아 실제 부하와 괴리
3. **복합 메트릭은 임계값 튜닝이 까다로움** — 낮은 임계값 설정 시 과도한 스케일업 → 리소스 경합 악순환
4. **로컬 kind 환경 한계** — 2 Worker에서 10 Pod은 물리 리소스 부족 유발

### 프로덕션 권장 설정

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 2        # 고가용성
  maxReplicas: 10~20    # 클러스터 용량에 따라
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 50
```

> 관련 결정: [ADR-008 CPU 기반 HPA Auto-scaling 전략](../adr/008-hpa-cpu-scaling-strategy.md)

---

## 실험 C: Kafka 파티션 · Consumer 병렬화

> **결론: 단일 브로커에서 파티션 증가는 성능 향상에 기여하지 않음**

### 조건
- Docker Compose (단일 Kafka 브로커)
- 1,000 VU Spike 패턴 (2분 30초)
- 재고 100,000장

### 결과

| 구성 | 파티션 / Consumer | RPS | p95 Latency | p99 Latency |
|------|-------------------|-----|-------------|-------------|
| **C-1** | **1 / 1** | **6,221** | **118.46ms** | **148.79ms** |
| C-2 | 3 / 3 | 5,523 | 168.88ms | 206.33ms |
| C-3 | 10 / 10 | 5,363 | 140.76ms | 159.39ms |

### 핵심 인사이트

1. **파티션 증가 ≠ 자동 성능 향상** — 단일 브로커에서는 I/O 분산 효과 없이 오버헤드만 추가
2. **단일 머신 한계** — CPU/디스크/메모리를 전부 공유하므로 컨테이너를 늘려도 물리 자원은 동일
3. **Consumer 병렬성은 DB 커넥션 풀과 함께 튜닝 필요** — HikariCP 기본 10개에서 Consumer 10개가 경합
4. **진정한 병렬화 효과는 물리적으로 분리된 다중 노드 환경에서만 기대 가능**

> 실험은 "파티션이 무의미"가 아니라, **단일 머신에서는 병렬화 효과를 측정할 수 없다**는 것을 보여줌

---

## 실험 D: 노드 장애 복구

> **결론: Detection Time 목표 달성 (< 1분), MTTR 목표 미달성 (> 10분)**

### 조건
- kind 클러스터 (Control Plane + Worker 2개)
- 3,000 VU 지속 (10분)
- 장애 주입: `docker stop` → 3분 후 `docker start`

### 결과

| 시나리오 | 장애 대상 | Detection | Node Recovery | Pod Recovery | MTTR | 성공률 |
|----------|----------|-----------|---------------|-------------|------|--------|
| D-1 | Infra 노드 (Kafka+DB) | 52s ✅ | 5s | 66s | > 10min ❌ | 18.02% |
| D-2 | App 노드 (App+Redis) | 46s ✅ | 6s | 0s (즉시) | > 10min ❌ | 24.37% |

### Probe 개선 효과 (Liveness/Readiness 분리)

| 항목 | D-1 초기 → 재테스트 | D-2 초기 → 재테스트 |
|------|---------------------|---------------------|
| App Pod 재시작 | 7회 → 7회 (변화 없음) | 5회 → **3회 (40% 감소 ✅)** |
| 성공률 | 18.02% → 6.21% | 24.37% → 11.78% |

### 핵심 인사이트

1. **Detection Time은 모든 실험에서 목표 달성** (46~56초, 목표 < 1분)
2. **인프라 노드 장애(D-1)가 더 심각** — Kafka/DB 동시 다운 시 Spring Boot 재연결 지연으로 대량 실패
3. **Probe 분리는 D-2에서 효과적** — Readiness 저하를 Liveness 실패로 오판하는 것을 방지
4. **근본 한계: 단일 Kafka 브로커 + 단일 레플리카** — 아키텍처 수준의 개선 없이는 MTTR 목표 달성 불가
5. **멱등성 동작 확인** — 장애 중에도 409 중복 발급 거절이 정상 작동

> 관련 결정: [ADR-009 장애 복구 전략 및 MTTR 최적화](../adr/009-resilience-recovery-strategy.md)

---

## 종합 결론

### 성과

| 항목 | 결과 |
|------|------|
| Kafka 비동기 파이프라인 | RPS 4,711/s, p50 12ms — Sync 대비 전 지표 우수 |
| Redis 원자적 재고 차감 | 100,000건 정확 발급, 초과 발급 0건 (모든 실험에서 동일) |
| HPA Auto-scaling | CPU 50% 기반이 최적, 8 Pod까지 자동 확장 |
| 장애 탐지 | 46~56초 이내 NotReady 전환 |
| 멱등성 | 모든 장애 시나리오에서 중복 발급 방지 정상 동작 |

### 한계 및 개선 방향

| 한계 | 원인 | 개선 방안 |
|------|------|----------|
| MTTR > 10분 | 단일 Kafka 브로커 + 단일 레플리카 | 다중 브로커(3+) + 다중 레플리카(2+) |
| 파티션 병렬화 효과 미확인 | 단일 머신 환경 | 다중 노드 Kafka 클러스터에서 재실험 |
| kind 클러스터 리소스 제약 | 로컬 2 Worker | 클라우드 K8s(EKS/GKE) 환경에서 재실험 |
| Spring Boot 재연결 지연 | 기본 backoff 설정 | HikariCP/Kafka reconnect 설정 튜닝 |

### 프로덕션 권고사항

1. **Kafka**: 최소 3 브로커, replication factor 3, `acks=all`
2. **HPA**: CPU 50% 기반, minReplicas 2, Prometheus Adapter로 Custom Metric(RPS, Consumer Lag) HPA 도입 검토
3. **DB 커넥션 풀**: 인스턴스 수 × HikariCP pool size ≤ DB max_connections
4. **Probe**: Liveness(`/actuator/health/liveness`)와 Readiness(`/actuator/health/readiness`) 반드시 분리
5. **Circuit Breaker**: Resilience4j 도입으로 인프라 장애 시 빠른 실패 응답
6. **Pod Anti-Affinity**: App과 Infra Pod를 서로 다른 노드에 강제 분리

---

## 실험 리포트 상세

| 실험 | 리포트 경로 |
|------|------------|
| A: DB vs Kafka | [`docs/reports/phase3/experiment-a-report.md`](phase3/experiment-a-report.md) |
| B: HPA Auto-scaling | [`docs/reports/phase3/experiment-b-report.md`](phase3/experiment-b-report.md) |
| C: Kafka 파티션 | [`docs/reports/phase3/experiment-c-report.md`](phase3/experiment-c-report.md) |
| D: 노드 장애 복구 | [`docs/reports/phase4/experiment-d-report.md`](phase4/experiment-d-report.md) |

## 관련 ADR

| # | 제목 | 관련 실험 |
|---|------|----------|
| [ADR-005](../adr/005-redis-atomic-stock.md) | Redis DECR 원자적 재고 차감 | A, B, C, D |
| [ADR-006](../adr/006-kafka-async-pipeline.md) | Kafka 비동기 파이프라인 | A, C |
| [ADR-007](../adr/007-async-over-sync-experiment.md) | 비동기 방식 성능 우위 실증 | A |
| [ADR-008](../adr/008-hpa-cpu-scaling-strategy.md) | CPU 기반 HPA 전략 | B |
| [ADR-009](../adr/009-resilience-recovery-strategy.md) | 장애 복구 전략 | D |
