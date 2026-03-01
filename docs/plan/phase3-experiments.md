# Phase 3: 성능 실험 및 검증 (2~3주)

## 3.1 부하 테스트 환경

### k6 시나리오 (`loadtest/k6/`)

#### 기본 시나리오 (`coupon-issue.js`)
```javascript
// 단계별 부하 증가
export const options = {
  stages: [
    { duration: '30s', target: 100 },    // Warm-up
    { duration: '1m',  target: 1000 },   // Ramp-up
    { duration: '2m',  target: 5000 },   // Sustained load
    { duration: '1m',  target: 10000 },  // Peak load
    { duration: '30s', target: 0 },      // Cool-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],     // 95% 응답 500ms 이하
    http_req_failed: ['rate<0.01'],       // 실패율 1% 미만
  },
};
```

#### Spike 시나리오 (`spike-test.js`)
```javascript
// 급격한 트래픽 폭주 시뮬레이션
export const options = {
  stages: [
    { duration: '10s', target: 100 },
    { duration: '5s',  target: 10000 },  // 급격한 spike
    { duration: '1m',  target: 10000 },  // 유지
    { duration: '10s', target: 0 },
  ],
};
```

### Locust 시나리오 (`loadtest/locust/`)

#### `locustfile.py`
```python
# k6와 동일 시나리오를 Locust로 구현 (비교 목적)
class CouponUser(HttpUser):
    wait_time = between(0.1, 0.5)

    @task
    def issue_coupon(self):
        self.client.post("/api/v1/coupons/issue", json={
            "couponEventId": 1,
            "userId": random.randint(1, 1000000)
        })
```

### Grafana 대시보드 구성
| 대시보드 | 패널 | 데이터 소스 |
|----------|------|-------------|
| **Application** | TPS, Latency (p50/p95/p99), Error Rate, Active Threads | Prometheus (Spring Actuator) |
| **Kafka** | Consumer Lag, Messages/sec, Partition Offset | Prometheus (Kafka Exporter) |
| **Redis** | Commands/sec, Memory Usage, Connected Clients | Prometheus (Redis Exporter) |
| **K8s** | CPU/Memory per Pod, Pod Count, HPA Status | Prometheus (kube-state-metrics) |

---

## 3.2 실험 A: DB 직결 vs Kafka 버퍼링 ✅ 완료

### 목적
동일 부하에서 동기(DB 직접) vs 비동기(Kafka 버퍼링) 성능 차이 측정

### 실험 설계

**A-1: Kafka 비동기 (기존 구현)**
```
Request → Redis 재고 DECR → Kafka Produce → 202 Accepted
         (Consumer가 비동기로 DB INSERT)
```

**A-2: DB 직결 동기**
```
Request → Redis 재고 DECR → DB INSERT (동기) → 200 OK
```

### 실험 결과 (500 VU)

| 항목 | Async (Kafka) | Sync (DB) | 비율 |
|------|--------------|-----------|------|
| **RPS (avg)** | **4,711/s** | **3,019/s** | **1.56x** |
| p50 Latency | **12.09ms** | 66.75ms | **5.5x 빠름** |
| p95 Latency | **46.98ms** | 96.19ms | **2.0x 빠름** |
| Max Latency | **86.25ms** | 359.27ms | **4.2x 빠름** |
| 성공률 | 100% | 100% | 동일 |
| DB 발급 건수 | 100,000 | 100,000 | 동일 |

### 결론
- Kafka 비동기 방식이 처리량 1.56배, p50 응답시간 5.5배 우위
- 재고 정합성은 양쪽 모두 100% 정확
- → **ADR-007**: Kafka 비동기 방식 성능 우위 실증 확인

> **상세 리포트**: [experiment-a-report.md](../reports/phase3/experiment-a-report.md)

---

## 3.3 실험 B: HPA Auto-scaling 최적화 ✅ 완료

### 목적
트래픽 폭주 시 CPU/Memory/Custom Metrics 중 최적 HPA 기준 탐색

### 실험 환경
- kind 클러스터 (Control Plane 1 + Worker 2)
- Spike: 0 → 10,000 VU (30초 ramp), 유지 5분

### 실험 결과

| 구성 | 최대 Pod | RPS | p95 Latency | Success Rate | 안정성 |
|------|----------|-----|-------------|-------------|--------|
| **B-1 CPU (50%)** | 8 | 1,329 | 24.42s | **76.76%** | Pod 재시작 없음 |
| B-2 Memory (70%) | 10 | 1,169 | 59.99s | 72.36% | OOM Kill 다수 |
| B-3 복합 (CPU 40% + Mem 60%) | 10 | 695 | 54.47s | 25.99% | CrashLoopBackOff |

> **참고**: B-3은 원래 Custom Metric (HTTP RPS)으로 계획했으나, Prometheus Adapter 미설치로 CPU+Memory 복합으로 대체.

### 결론
- CPU 기반 HPA가 가장 안정적이고 예측 가능한 스케일링 트리거
- Memory 기반은 JVM GC 특성으로 부하와 메모리 사이에 괴리 발생, OOM Kill 위험
- 복합 메트릭은 과도한 스케일업 → 리소스 경합 → CrashLoopBackOff
- → **ADR-008**: CPU 기반 HPA Auto-scaling 전략 채택

> **상세 리포트**: [experiment-b-report.md](../reports/phase3/experiment-b-report.md)

---

## 3.4 실험 C: Kafka 파티션 튜닝 ✅ 완료

### 목적
파티션 수와 Consumer concurrency를 함께 늘릴 때 DB INSERT 처리량이 비례 증가하는지 실증

### 실험 설계

| 구성 | Partitions | Consumer concurrency |
|------|-----------|---------------------|
| C-1 | 1 | 1 |
| C-2 | 3 | 3 |
| C-3 | 10 | 10 |

### 실험 결과 (1,000 VU Spike)

| 지표 | C-1 (1/1) | C-2 (3/3) | C-3 (10/10) |
|------|-----------|-----------|-------------|
| RPS (avg) | 6,221 | 5,523 | 5,363 |
| Latency p95 | 118.46ms | 168.88ms | 140.76ms |
| Latency p99 | 148.79ms | 206.33ms | 159.39ms |
| DB 발급 건수 | 100,000 | 100,000 | 100,000 |

### 결론
- 단일 브로커 Docker Compose 환경에서는 파티션 증가가 처리량 향상에 기여하지 않음
- 오히려 파티션 관리 오버헤드로 미세한 성능 저하 관찰
- 파티션 병렬화 효과는 **다중 브로커 + 다중 노드** 환경에서 발현

> **상세 리포트**: [experiment-c-report.md](../reports/phase3/experiment-c-report.md)

---

## 리포트 템플릿

각 실험 완료 후 다음 형식으로 리포트 작성:

```markdown
# 실험 [A/B/C] 결과 리포트

## 실험 환경
- 하드웨어: (CPU, RAM)
- K8s: kind (Master 1, Worker 2)
- 부하 도구: k6 / Locust

## 실험 조건
(부하 패턴, 재고 수량, 반복 횟수)

## 결과
(테이블 + Grafana 캡처)

## 분석
(병목 지점, 원인 분석)

## 결론 및 개선 방향
(Troubleshooting 포함)
```

---

## 3.5 KPI 달성 검증 (100k TPS)

### 검증 항목
| KPI | 목표 | 측정 방법 |
|-----|------|-----------|
| 피크 처리량 | 100k TPS | k6 `http_reqs` rate |
| 성공률 | ≥ 99.9% | k6 `http_req_failed` < 0.1% |
| Latency p95 | ≤ 200ms | k6 `http_req_duration` p(95) |
| Latency p99 | ≤ 500ms | k6 `http_req_duration` p(99) |
| Kafka Lag | SLA 내 회복 | Consumer lag 모니터링 |
| Backlog 해소 시간 | 측정 | 부하 종료 → lag 0 도달 시간 |

### 검증 절차
1. 실험 C 최적 구성(파티션/Consumer 수)으로 고정
2. 점진적 부하: 10k → 50k → 100k TPS
3. 100k TPS에서 5분간 유지
4. 모든 KPI 동시 측정
5. 3회 반복 후 평균값으로 판정

---

## Phase 3 완료 기준
- [x] k6 시나리오 작성 및 실행 가능 (Locust는 k6로 대체)
- [x] 실험 A/B/C 실행 완료
- [x] Grafana 대시보드에서 실시간 모니터링 확인 (Prometheus + kube-prometheus-stack)
- [x] 실험별 결과 리포트 작성 완료 (A/B/C 각각 + ADR-007, ADR-008)
- [x] 병목 지점 식별: HikariCP 풀 경합(A), JVM GC와 Memory HPA 괴리(B), 단일 브로커 파티션 한계(C)
- [ ] KPI 달성 검증 (100k TPS) — 프로덕션 멀티 노드 환경에서 별도 검증 필요
