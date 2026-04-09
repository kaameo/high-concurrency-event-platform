# Phase 5: 클라우드 Kubernetes 환경 실험 계획

> kind 로컬 클러스터의 구조적 한계를 클라우드 환경(EKS)에서 해소하고, 다중 노드 분산 아키텍처의 실제 성능을 검증한다.

## 배경

Phase 3~4 실험에서 확인된 로컬 환경 한계:

| 한계 | 원인 | Phase 5 목표 |
|------|------|-------------|
| MTTR > 10분 | 단일 Kafka 브로커 + 단일 레플리카 | 다중 브로커(3+)로 MTTR < 10분 달성 |
| 파티션 병렬화 효과 미확인 | 단일 머신 I/O 공유 | 다중 노드에서 파티션 병렬화 효과 실측 |
| kind 리소스 제약 (2 Worker) | 로컬 머신 물리 자원 한계 | 클라우드 노드 3~5대로 확장 |
| HPA 실험 왜곡 | 과밀 Pod → 리소스 경합 | 충분한 노드 리소스에서 HPA 재검증 |

> 관련: [ADR-009 장애 복구 전략](../adr/009-resilience-recovery-strategy.md) — "Kafka 다중 브로커 구성이 MTTR 목표 달성을 위한 핵심 과제"

---

## 환경 선택: AWS EKS + Spot Instance

> 관련 결정: [ADR-010 클라우드 K8s 환경 선택](../adr/010-cloud-k8s-environment.md)

### 클러스터 구성

```yaml
클러스터:
  이름: event-platform-test
  리전: ap-northeast-2 (서울)
  노드:
    타입: Spot Instance (m5.large / m5a.large)
    수량: 3~5대
    vCPU: 2 (per node)
    Memory: 8GB (per node)
  전략: 실험 시에만 생성 → 완료 후 즉시 삭제
  예상 비용: ~$2-5 / 실험 세션 (3시간 기준)
```

### 인프라 배포 구성

| 컴포넌트 | kind (Phase 3~4) | EKS (Phase 5) | 변경 사항 |
|----------|-----------------|---------------|----------|
| Kafka | 단일 브로커 (StatefulSet) | **3 브로커 (Bitnami Helm)** | replication.factor=3, min.insync=2 |
| PostgreSQL | 단일 인스턴스 (Helm) | 단일 인스턴스 (Helm) | 동일 (DB는 병목 아님) |
| Redis | 단일 인스턴스 (Helm) | 단일 인스턴스 (Helm) | 동일 |
| App | replicas 1~2, HPA | **replicas 2, HPA max 10** | 노드 분산 + Anti-Affinity |
| Monitoring | Prometheus + Grafana | Prometheus + Grafana | 동일 |

---

## 실험 계획

### 실험 E: 다중 브로커 Kafka 파티션 병렬화

> Phase 3 실험 C의 후속 — 단일 브로커 한계를 다중 브로커에서 재검증

**가설**: 3 브로커 환경에서 파티션 증가 시 Consumer 병렬 처리 효과가 실측될 것이다.

| 구성 | 브로커 | 파티션 / Consumer | 비교 대상 |
|------|--------|-------------------|----------|
| E-1 | 3 | 1 / 1 | C-1 베이스라인 |
| E-2 | 3 | 3 / 3 | C-2 (단일 브로커 3파티션) |
| E-3 | 3 | 9 / 9 | 브로커당 3파티션 최적 분배 |

**부하 조건**: 1,000 VU Spike (2분 30초) — 실험 C와 동일 조건

**측정 지표**:
- RPS, p50/p95/p99 Latency
- Kafka Consumer Lag (파티션별)
- 브로커별 I/O 분산율
- 재고 정합성 (100,000건)

**성공 기준**: E-3 RPS > C-1 RPS (6,221/s) 또는 파티션 증가에 따른 선형 성능 향상 확인

---

### 실험 F: 클라우드 HPA Auto-scaling 재검증

> Phase 3 실험 B의 후속 — 충분한 노드 리소스에서 HPA 재검증

**가설**: 노드 리소스 충분 시 CPU 50% HPA가 kind 대비 높은 성공률과 낮은 Latency를 달성한다.

| 구성 | 메트릭 | 노드 | maxReplicas | 비교 대상 |
|------|--------|------|-------------|----------|
| F-1 | CPU 50% | 3 (Spot) | 10 | B-1 (kind, 76.76%) |
| F-2 | CPU 50% | 5 (Spot) | 15 | 노드 확장 효과 |

**부하 조건**: 10,000 VU Spike (30초 ramp, 5분 유지) — 실험 B와 동일

**측정 지표**:
- RPS, p95 Latency, 성공률
- Pod 스케일업 속도 (초)
- 노드별 리소스 사용률
- Pod 재시작 횟수

**성공 기준**: 성공률 > 90% (B-1: 76.76%), p95 Latency < 10s (B-1: 24.42s)

---

### 실험 G: 다중 브로커 장애 복구

> Phase 4 실험 D의 후속 — 다중 브로커 아키텍처에서 MTTR 재측정

**가설**: 3 브로커 + replication factor 3 환경에서 1 브로커 장애 시 MTTR < 10분을 달성한다.

| 시나리오 | 장애 대상 | 조건 |
|----------|----------|------|
| G-1 | Kafka 브로커 1대 종료 | 3,000 VU 지속, 3분 후 복구 |
| G-2 | App 노드 1대 종료 | 3,000 VU 지속, replicas 2 + Anti-Affinity |
| G-3 | Kafka 브로커 1대 + App 노드 1대 동시 종료 | 복합 장애 시나리오 |

**측정 지표**:
- Detection Time (목표 < 1분)
- MTTR (목표 < 10분)
- 장애 중 성공률
- 데이터 정합성 (재고, 멱등성)
- Consumer Rebalance 시간

**성공 기준**: G-1, G-2 MTTR < 10분, 멱등성 정상 동작

---

## 실행 절차

### 1. 클러스터 생성

```bash
# eksctl로 Spot 클러스터 생성
eksctl create cluster \
  --name event-platform-test \
  --region ap-northeast-2 \
  --spot \
  --instance-types m5.large,m5a.large \
  --nodes 3 \
  --nodes-min 3 \
  --nodes-max 5

# metrics-server 설치 (HPA 필수)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

### 2. 인프라 배포

```bash
# Kafka 3 브로커 (Bitnami Helm)
helm install kafka oci://registry-1.docker.io/bitnamicharts/kafka \
  -f k8s/values/kafka-values-eks.yaml

# PostgreSQL + Redis (기존 values 재사용)
helm install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql \
  -f k8s/values/postgresql-values.yaml
helm install redis oci://registry-1.docker.io/bitnamicharts/redis \
  -f k8s/values/redis-values.yaml

# Prometheus + Grafana
helm install prometheus prometheus-community/kube-prometheus-stack
```

### 3. 앱 배포

```bash
# ECR에 이미지 푸시
aws ecr create-repository --repository-name event-platform
docker build -t <account>.dkr.ecr.ap-northeast-2.amazonaws.com/event-platform:latest .
docker push <account>.dkr.ecr.ap-northeast-2.amazonaws.com/event-platform:latest

# EKS용 매니페스트 적용
kubectl apply -f k8s/app-eks/
```

### 4. 부하 테스트 실행

```bash
# 실험 E (Kafka 파티션 병렬화)
k6 run k6/experiment-e.js

# 실험 F (HPA)
k6 run k6/experiment-f.js

# 실험 G (장애 복구)
chmod +x k8s/run-experiment-g.sh
./k8s/run-experiment-g.sh
```

### 5. 결과 수집 및 정리

```bash
# Grafana 대시보드 스크린샷 캡처
# k6 결과 JSON 저장
# kubectl top nodes/pods 스냅샷
# Kafka Consumer Lag 기록
```

### 6. 클러스터 삭제

```bash
# 실험 완료 후 즉시 삭제 — 비용 절감 핵심
eksctl delete cluster --name event-platform-test --region ap-northeast-2
```

---

## 추가 작업 (EKS 배포 준비)

### 신규 파일

| 파일 | 용도 |
|------|------|
| `k8s/values/kafka-values-eks.yaml` | 3 브로커 Kafka Helm values |
| `k8s/app-eks/deployment.yaml` | ECR 이미지 + Anti-Affinity |
| `k8s/app-eks/hpa.yaml` | EKS용 HPA (maxReplicas 확대) |
| `k8s/run-experiment-g.sh` | 실험 G 자동화 스크립트 |
| `k6/experiment-e.js` | 실험 E 부하 스크립트 |
| `k6/experiment-f.js` | 실험 F 부하 스크립트 |

### 기존 설정 변경

| 항목 | 변경 내용 |
|------|----------|
| Kafka `replication.factor` | 1 → 3 |
| Kafka `min.insync.replicas` | 1 → 2 |
| Kafka Producer `acks` | 1 → all |
| App `topologySpreadConstraints` | kind → EKS 노드 레이블 적용 |
| App image | kind load → ECR pull |

---

## 비용 관리

| 항목 | 단가 | 예상 사용 | 비용 |
|------|------|----------|------|
| EKS 클러스터 | $0.10/h | 3h × 3회 | $0.90 |
| Spot m5.large × 3 | ~$0.05/h each | 3h × 3회 | $1.35 |
| ECR 스토리지 | ~$0.10/GB/월 | 1 이미지 | ~$0.01 |
| 데이터 전송 | $0.09/GB | ~5GB | $0.45 |
| **합계** | | | **~$3-5** |

> 핵심 원칙: **실험할 때만 클러스터 생성, 결과 캡처 후 즉시 삭제**

---

## 산출물

| 산출물 | 경로 |
|--------|------|
| 실험 E 리포트 | `docs/reports/phase5/experiment-e-report.md` |
| 실험 F 리포트 | `docs/reports/phase5/experiment-f-report.md` |
| 실험 G 리포트 | `docs/reports/phase5/experiment-g-report.md` |
| Phase 5 종합 리포트 | `docs/reports/phase5/summary.md` |
| summary.md 업데이트 | `docs/reports/summary.md` (Phase 5 결과 추가) |
| ADR-010 | `docs/adr/010-cloud-k8s-environment.md` |

---

## 타임라인

| 단계 | 작업 | 예상 소요 |
|------|------|----------|
| 준비 | EKS 매니페스트 작성, Kafka values 작성, k6 스크립트 작성 | 1일 |
| 실험 세션 1 | 클러스터 생성 → 실험 E (파티션 병렬화) | 3시간 |
| 실험 세션 2 | 클러스터 생성 → 실험 F (HPA) + 실험 G (장애 복구) | 3시간 |
| 정리 | 리포트 작성, summary.md 업데이트, README 반영 | 1일 |
