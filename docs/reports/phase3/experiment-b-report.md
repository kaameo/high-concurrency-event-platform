# 실험 B: HPA Auto-scaling 최적화 결과

- **실험일**: 2026-03-01
- **환경**: kind 클러스터 (macOS, Docker Desktop)

## 실험 개요

| 항목 | 내용 |
|------|------|
| 목적 | K8s HPA 메트릭별 Auto-scaling 성능 비교 |
| 환경 | kind 클러스터 (Control Plane 1 + Worker 2) |
| 부하 | k6 Spike: 0 → 10,000 VU (30초 ramp), 유지 5분 |
| 앱 | Spring Boot 4 + Virtual Threads + Kafka 비동기 |
| 인프라 | PostgreSQL (Helm), Redis (Helm), Kafka (apache/kafka:3.9.0 매니페스트) |

## HPA 구성

| 구성 | 메트릭 | 타겟 | min/max Replicas |
|------|--------|------|------------------|
| B-1 | CPU Utilization | 50% | 1 / 10 |
| B-2 | Memory Utilization | 70% | 1 / 10 |
| B-3 | CPU 40% + Memory 60% (복합) | 복합 | 1 / 10 |

> **참고**: B-3은 원래 Custom Metric (HTTP RPS)으로 계획했으나, Prometheus Adapter 없이는
> kind 환경에서 커스텀 메트릭 수집이 불가하여 CPU+Memory 복합 메트릭으로 대체.

## 실험 결과

### B-1: CPU 기반 HPA (averageUtilization: 50%)

| 지표 | 값 |
|------|-----|
| 최대 CPU 사용률 | 399% |
| 최종 Pod 수 | **8** |
| Total Requests | 505,767 |
| RPS (avg) | 1,329 |
| Latency avg | 6.16s |
| Latency p95 | 24.42s |
| Coupons Issued | 13,389 |
| Coupons Sold Out | 367,343 |
| Success Rate | **76.76%** |
| Errors | 117,521 |

### B-2: Memory 기반 HPA (averageUtilization: 70%)

| 지표 | 값 |
|------|-----|
| 최대 Memory 사용률 | 102% |
| 최종 Pod 수 | **10** (최대) |
| Total Requests | 432,702 |
| RPS (avg) | 1,169 |
| Latency avg | 7.44s |
| Latency p95 | 59.99s |
| Coupons Issued | 11,605 |
| Coupons Sold Out | 284,401 |
| Success Rate | **72.36%** |
| Errors | 119,567 |
| Pod 재시작 | 다수 (OOM Kill 패턴) |

### B-3: CPU+Memory 복합 HPA (CPU 40% + Memory 60%)

| 지표 | 값 |
|------|-----|
| 최종 Pod 수 | **10** (최대) |
| Total Requests | 267,236 |
| RPS (avg) | 695 |
| Latency avg | 11.74s |
| Latency p95 | 54.47s |
| Coupons Issued | 10,401 |
| Coupons Sold Out | 53,839 |
| Success Rate | **25.99%** |
| Errors | 197,761 |
| Pod 상태 | CrashLoopBackOff 다수 |

## 비교 분석

| 구성 | 최대 Pod | Total Req | RPS | p95 Latency | Success Rate | 특이사항 |
|------|----------|-----------|-----|-------------|-------------|----------|
| B-1 CPU | 8 | 505,767 | 1,329 | 24.42s | **76.76%** | 가장 안정적 |
| B-2 Memory | 10 | 432,702 | 1,169 | 59.99s | 72.36% | OOM 재시작 발생 |
| B-3 복합 | 10 | 267,236 | 695 | 54.47s | 25.99% | CrashLoop 다수 |

## 핵심 관찰

### 1. 스케일업 반응 속도
- **B-1 CPU**: HPA가 CPU 급등을 감지하여 약 15~30초 내에 스케일업 시작. 가장 빠른 반응.
- **B-2 Memory**: Memory 증가는 상대적으로 느리게 감지됨. JVM 힙 특성상 GC 전까지 메모리가 꾸준히 증가.
- **B-3 복합**: 두 메트릭 중 하나만 초과해도 스케일하여 가장 공격적으로 확장.

### 2. 메트릭별 민감도
- **CPU**는 요청 처리량에 직접 비례하여 가장 예측 가능한 스케일링 트리거.
- **Memory**는 JVM 특성(GC, 힙 관리)으로 인해 실제 부하와 메모리 사용률 사이에 지연 존재.
- **복합 메트릭**은 낮은 임계값(CPU 40%, Mem 60%) 설정 시 과도한 스케일업 → 리소스 경합 악순환.

### 3. 안정성
- **B-1 CPU**가 유일하게 Pod 재시작 없이 안정적으로 운영됨.
- **B-2, B-3**는 Worker 노드의 리소스 한계로 Pod OOM Kill과 CrashLoopBackOff 발생.
- 로컬 kind 환경(2 Worker)에서 10 Pod 동시 운영은 리소스 부족을 유발.

## 결론 및 권장사항

### 권장: CPU 기반 HPA (B-1)

1. **CPU 메트릭이 가장 안정적이고 예측 가능**한 스케일링 트리거
2. 프로덕션 권장 설정:
   - `averageUtilization: 50~60%`
   - `minReplicas: 2` (고가용성)
   - `maxReplicas`: 클러스터 용량에 따라 조정
3. Memory 기반 HPA는 JVM 앱에서는 **비권장** — GC 특성으로 메모리가 즉시 반환되지 않아 불필요한 스케일업 유발
4. 복합 메트릭은 임계값 튜닝이 까다롭고, 과도한 스케일업으로 오히려 성능 저하 가능
5. **Custom Metric (HTTP RPS) 기반 HPA**는 Prometheus Adapter 설치 후 별도 실험 필요

### 로컬 환경 한계

- kind 클러스터(2 Worker)에서 10,000 VU는 과부하 — 프로덕션 환경에서 재실험 권장
- NodePort를 통한 단일 진입점이 병목 — 프로덕션에서는 LoadBalancer/Ingress 사용

## 실행 방법

```bash
# 전체 실험 (클러스터 생성 → 인프라 배포 → 실험 실행)
chmod +x k8s/run-experiment-b.sh
./k8s/run-experiment-b.sh

# 개별 k6 실행
k6 run --env BASE_URL=http://localhost:30080 --env HPA_CONFIG=cpu k6/phase3-experiment-b-hpa.js
k6 run --env BASE_URL=http://localhost:30080 --env HPA_CONFIG=memory k6/phase3-experiment-b-hpa.js
k6 run --env BASE_URL=http://localhost:30080 --env HPA_CONFIG=custom k6/phase3-experiment-b-hpa.js

# HPA 모니터링
kubectl get hpa -w
kubectl get pods -l app=event-platform -w
```
