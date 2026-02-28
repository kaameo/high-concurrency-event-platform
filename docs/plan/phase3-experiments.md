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

## 3.2 실험 A: DB 직결 vs Kafka 버퍼링

### 목적
동일 부하에서 동기(DB 직접) vs 비동기(Kafka 버퍼링) 성능 차이 측정

### 실험 설계

**A-1: DB 직결 방식**
```
Request → Redis 재고 확인 → DB INSERT (동기) → Response
```
- 별도 API 엔드포인트: `POST /api/v1/coupons/issue-sync`
- 동일한 Redis 재고 차감 로직 사용
- DB INSERT 후 바로 200 OK 응답

**A-2: Kafka 버퍼링 방식 (기존 구현)**
```
Request → Redis 재고 확인 → Kafka Produce → 202 Accepted
         (Consumer가 비동기로 DB INSERT)
```

### 측정 항목
| 항목 | 측정 방법 |
|------|-----------|
| 응답 시간 (p50/p95/p99) | k6 `http_req_duration` |
| 처리량 (TPS) | k6 `http_reqs` |
| DB 커넥션 풀 사용량 | HikariCP 메트릭 |
| Eventual Consistency 지연 | Kafka → DB 저장까지 시간 (Consumer 메트릭) |
| 에러율 | k6 `http_req_failed` |

### 부하 조건
- VU(Virtual Users): 100 → 500 → 1,000 → 5,000 → 10,000
- 쿠폰 재고: 100,000장
- 각 조건에서 5분 유지, 3회 반복

### 기대 결과
- Kafka 방식: 응답 시간 90%+ 개선 (DB blocking 제거)
- DB 직결: 커넥션 풀 고갈 시점 확인
- Eventual Consistency: Kafka Consumer lag 기반 지연 시간

---

## 3.3 실험 B: HPA Auto-scaling 최적화

### 목적
트래픽 폭주 시 CPU/Memory/Custom Metrics 중 최적 HPA 기준 탐색

### 실험 설계

**B-1: CPU 기반 HPA**
```yaml
metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 50
```

**B-2: Memory 기반 HPA**
```yaml
metrics:
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 70
```

**B-3: Custom Metrics (HTTP Request Rate)**
```yaml
metrics:
  - type: Pods
    pods:
      metric:
        name: http_server_requests_per_second
      target:
        type: AverageValue
        averageValue: "1000"
```
- Prometheus Adapter 설치 필요

### HPA 공통 설정
```yaml
minReplicas: 1
maxReplicas: 10
behavior:
  scaleUp:
    stabilizationWindowSeconds: 30
    policies:
      - type: Pods
        value: 2
        periodSeconds: 60
  scaleDown:
    stabilizationWindowSeconds: 300
```

### 측정 항목
| 항목 | 측정 방법 |
|------|-----------|
| 스케일업 반응 시간 | 부하 시작 → 새 Pod Ready까지 시간 |
| 스케일업 중 응답 시간 | 스케일링 진행 중 p95 latency |
| 최대 Pod 수 도달 시간 | 10 Pod까지 도달 소요 시간 |
| 오버프로비저닝 여부 | 부하 해제 후 불필요한 Pod 유지 시간 |

### 부하 조건
- Spike 패턴: 0 → 10,000 VU (30초 내)
- 각 HPA 설정별 동일 시나리오 3회 반복

---

## 3.4 실험 C: Kafka 파티션 튜닝

### 목적
파티션 수와 Consumer 수의 관계가 처리량에 미치는 영향 측정

### 실험 설계

| 시나리오 | Partitions | Consumers | 목표 TPS | 기대 |
|----------|-----------|-----------|----------|------|
| C-1 | 1 | 1 | 10k | Baseline |
| C-2 | 10 | 10 | 50k | 10배 파티션 확장 효과 |
| C-3 | 50 | 50 | 100k | 최대 목표 달성 |
| C-4 | 50 | 10 | - | Consumer < Partition (lag 증가) |
| C-5 | 10 | 50 | - | Consumer > Partition (유휴 Consumer) |

### 측정 항목
| 항목 | 측정 방법 |
|------|-----------|
| Consumer 처리량 (msg/sec) | Kafka Consumer 메트릭 |
| Consumer Lag | Kafka Offset 모니터링 |
| End-to-End 지연 | Produce timestamp → DB 저장 timestamp |
| 파티션 간 데이터 분포 | 각 파티션별 메시지 수 |

### Partition Key 전략
- Key: `userId` — 동일 사용자 발급 이벤트는 같은 파티션에 순서 보장
- 사용자 수가 충분히 많으므로 파티션 간 자연 부하 분산

### 부하 조건
- 지속적 5,000 TPS, 5분간
- 쿠폰 재고: 1,000,000장 (파티션 성능에 집중하기 위해 재고 충분히 확보)

### 기대 결과
- C-1 → C-3: 처리량 단계적 증가 (10k → 50k → 100k TPS)
- C-4: 40개 파티션에 Consumer 미배정, lag 급증
- C-5: 40개 Consumer 유휴, 자원 낭비 확인

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
- [ ] k6 + Locust 시나리오 작성 및 실행 가능
- [ ] 실험 A/B/C 각 3회 이상 반복 실행
- [ ] Grafana 대시보드에서 실시간 모니터링 확인
- [ ] 실험별 결과 리포트 작성 완료
- [ ] 병목 지점 최소 3건 식별 및 해결
- [ ] KPI 달성 검증 완료 (100k TPS, 99.9% 성공률, p95 ≤ 200ms)
