# Phase 4: 장애 복구 실험 결과 리포트

> 실험일: 2026-03-01
> 환경: kind 클러스터 (Control-plane + Worker 1 [app/redis] + Worker 2 [infra])

### 노드별 Pod 배치

| 노드 | 호스트명 | 주요 Pod |
|------|----------|---------|
| Worker 1 | event-platform-worker | App, Redis, Alertmanager, Prometheus Operator |
| Worker 2 | event-platform-worker2 | Kafka, PostgreSQL, Grafana, Prometheus |

---

## 실험 D-1: Infra 노드 장애 (Worker 2)

### 시나리오
- 부하: 3,000 VU 지속 (10분)
- 장애 주입: Worker 2 (`docker stop`) — Kafka, PostgreSQL 노드
- 복구: 3분 후 `docker start`

### 초기 실험 결과

#### 타임라인

| 이벤트 | 시각 | 경과 |
|--------|------|------|
| 노드 종료 (`docker stop`) | 17:03:34 | 0s |
| NotReady 전환 | 17:04:26 | +52s |
| `docker start` 실행 | 17:07:26 | +3min 52s |
| 노드 Ready | 17:07:31 | +5s (docker start 기준) |
| Pod 전체 Running | 17:08:37 | +66s (docker start 기준) |

#### k6 부하 결과

| 지표 | 값 |
|------|----|
| 총 요청 수 | 134,261 |
| 평균 RPS | 209 req/s |
| 쿠폰 발급 성공 | 9,861건 |
| 중복 발급 (409) | 1,931건 |
| 재고 소진 (Sold Out) | 12,402건 |
| 에러 (5xx/timeout) | 110,067건 |
| 성공률 | 18.02% |
| p95 응답시간 | 29.99s |

#### MTTR

| 지표 | 값 | 목표 |
|------|-----|------|
| Detection Time (종료 → NotReady) | 52s | < 1분 ✅ |
| Node Recovery (docker start → Ready) | 5s | — |
| Pod Recovery (docker start → Running) | 66s | — |
| Service Restoration | 미달성 (테스트 종료 시까지 미회복) | < 8분 ❌ |
| **Total MTTR** | **> 10분** | **< 10분 ❌** |

#### 관찰 사항
- 노드 NotReady 전환은 52초로 1분 이내 목표 달성
- Worker 2 복구(docker start) 후 노드 Ready까지 5초, Pod Running까지 66초로 인프라 자체 재시작은 빠름
- Kafka 및 PostgreSQL 복구 후 Spring Boot 애플리케이션의 재연결 지연으로 connection reset 에러가 k6 테스트 종료 시까지 지속
- 인프라(Kafka/DB) 유실로 인한 장애가 가장 심각 — 재연결 backoff 동안 대부분 요청 실패
- 10분 테스트 창 내 TPS 정상 회복 미달성
- App Pod 재시작 횟수: 7회 (Liveness Probe가 단일 `/actuator/health` 엔드포인트로 구성되어, Readiness 저하를 Liveness 실패로 오판하여 불필요한 재시작 발생)

#### TPS 추이 (개략)
```
TPS
 ▲
 │  ████████
 │          █
 │          █  ← Kafka/DB 재연결 지연 (connection reset 지속)
 │          █
 │          █████  ← 일부 회복 시도 (테스트 종료 전)
 │
 └──────────────────── 시간
    T_STOP  T_NOTREADY  T_RECOVER  T_NODE_READY  T_PODS_READY
```

---

### 재테스트 결과 (Probe 개선 후)

> 적용 변경사항: Liveness/Readiness Probe 분리
> - Liveness: `/actuator/health/liveness`, timeout 5s, failureThreshold 6, initialDelaySeconds 90
> - Readiness: `/actuator/health/readiness`

#### 타임라인

| 이벤트 | 시각 | 경과 |
|--------|------|------|
| 노드 종료 (`docker stop`) | 17:56:13 | 0s |
| NotReady 전환 | 17:57:09 | +56s |
| `docker start` 실행 | — | +3min |
| 노드 Ready | 18:00:15 | +6s (docker start 기준) |
| Pod 전체 Running | 18:00:25 | +10s (docker start 기준) |

#### k6 부하 결과

| 지표 | 값 |
|------|----|
| 총 요청 수 | 122,272 |
| 평균 RPS | 191 req/s |
| 쿠폰 발급 성공 | 6,939건 |
| 중복 발급 (409) | 660건 |
| 재고 소진 (Sold Out) | 0건 |
| 에러 (5xx/timeout) | 114,673건 |
| 성공률 | 6.21% |
| p95 응답시간 | 15,002.23ms |

#### MTTR

| 지표 | 값 | 목표 |
|------|-----|------|
| Detection Time (종료 → NotReady) | 56s | < 1분 ✅ |
| Node Recovery (docker start → Ready) | 6s | — |
| Pod Recovery (docker start → Running) | 10s | — |
| App Pod 재시작 횟수 | 7회 (여전히 반복) | — |
| Service Restoration | 미달성 (10분 내 미회복) | < 8분 ❌ |
| **Total MTTR** | **> 10분** | **< 10분 ❌** |

#### 관찰 사항
- Detection Time 56초로 목표 1분 이내 달성
- App Pod 재시작 횟수는 초기 실험과 동일하게 7회 — Infra 노드 장애 시 Kafka/DB가 함께 다운되므로 Probe 분리만으로는 재시작 억제 효과 없음
- Kafka/DB 복구 후에도 Spring Boot 재연결 지연이 지속되어 성공률 6.21%로 오히려 초기 실험(18.02%)보다 낮음 (재고 소진 0건 — Kafka 연결 실패로 발급 자체가 거의 이루어지지 않음)
- 인프라 의존성이 근본 원인 — Probe 개선의 효과는 Infra 노드 장애 시나리오에서 미미함

---

## 실험 D-2: App 노드 장애 (Worker 1)

### 시나리오
- 부하: 3,000 VU 지속 (10분)
- 장애 주입: Worker 1 (`docker stop`) — Spring Boot App, Redis 노드
- 복구: 3분 후 `docker start`

### 초기 실험 결과

#### 타임라인

| 이벤트 | 시각 | 경과 |
|--------|------|------|
| 노드 종료 (`docker stop`) | 17:26:01 | 0s |
| NotReady 전환 | 17:26:47 | +46s |
| `docker start` 실행 | 17:29:47 | +3min 46s |
| 노드 Ready | 17:29:53 | +6s (docker start 기준) |
| Pod 전체 Running | 17:29:53 | +0s (즉시, 동일 노드 재배치) |

#### k6 부하 결과

| 지표 | 값 |
|------|----|
| 총 요청 수 | 146,841 |
| 평균 RPS | 229 req/s |
| 쿠폰 발급 성공 | 9,854건 |
| 중복 발급 (409) | 2,824건 |
| 재고 소진 (Sold Out) | 23,110건 |
| 에러 (5xx/timeout) | 111,053건 |
| 성공률 | 24.37% |
| p95 응답시간 | 29.99s |

#### MTTR

| 지표 | 값 | 목표 |
|------|-----|------|
| Detection Time (종료 → NotReady) | 46s | < 1분 ✅ |
| Node Recovery (docker start → Ready) | 6s | — |
| Pod Recovery (docker start → Running) | 0s (즉시) | — |
| Service Restoration | 미달성 (App Pod 반복 재시작으로 미회복) | < 8분 ❌ |
| **Total MTTR** | **> 10분** | **< 10분 ❌** |

#### 관찰 사항
- 노드 NotReady 전환은 46초로 D-1보다 빠름 (목표 달성)
- Worker 1 복구 후 Pod가 동일 노드에 즉시 재배치 (0s) — 인프라(Kafka/DB)가 Worker 2에 유지되었기 때문
- App Pod가 5회 재시작을 반복하며 안정화 지연
- Redis도 Worker 1에서 1회 재시작
- D-1 대비 성공률이 다소 높음(18% → 24%)은 인프라가 유지되었기 때문
- 그러나 App Pod의 반복 재시작으로 10분 테스트 창 내 TPS 정상 회복 미달성

#### 자원 경합 관찰

| 지표 | 비고 |
|------|------|
| App CPU | Worker 1 복구 후 App + Redis 공존, 재시작 반복으로 부하 증가 |
| Kafka Consumer Lag | 인프라 정상 유지 — Consumer rebalance 이후 처리 재개 |
| Redis 재시작 | Worker 1에서 1회 재시작 관찰 |
| App Pod 재시작 횟수 | 5회 |

---

### 재테스트 결과 (Probe 개선 후)

> 적용 변경사항: Liveness/Readiness Probe 분리
> - Liveness: `/actuator/health/liveness`, timeout 5s, failureThreshold 6, initialDelaySeconds 90
> - Readiness: `/actuator/health/readiness`

#### 타임라인

| 이벤트 | 시각 | 경과 |
|--------|------|------|
| 노드 종료 (`docker stop`) | 18:10:47 | 0s |
| NotReady 전환 | 18:11:34 | +47s |
| `docker start` 실행 | — | +3min |
| 노드 Ready | 18:14:39 | +5s (docker start 기준) |
| Pod 전체 Running | 18:14:39 | +0s (즉시) |

#### k6 부하 결과

| 지표 | 값 |
|------|----|
| 총 요청 수 | 129,884 |
| 평균 RPS | 206 req/s |
| 쿠폰 발급 성공 | 9,760건 |
| 중복 발급 (409) | 1,290건 |
| 재고 소진 (Sold Out) | 4,252건 |
| 에러 (5xx/timeout) | 114,582건 |
| 성공률 | 11.78% |
| p95 응답시간 | 15,004.86ms |

#### MTTR

| 지표 | 값 | 목표 |
|------|-----|------|
| Detection Time (종료 → NotReady) | 47s | < 1분 ✅ |
| Node Recovery (docker start → Ready) | 5s | — |
| Pod Recovery (docker start → Running) | 0s (즉시) | — |
| App Pod 재시작 횟수 | 3회 (초기 5회 → 40% 감소 ✅) | — |
| Service Restoration | 미달성 (10분 내 미회복) | < 8분 ❌ |
| **Total MTTR** | **> 10분** | **< 10분 ❌** |

#### 관찰 사항
- Detection Time 47초로 목표 1분 이내 달성
- App Pod 재시작 횟수 5회 → 3회로 40% 감소 — Liveness/Readiness Probe 분리 효과 확인
- Liveness Probe 분리로 Readiness 저하 시 불필요한 컨테이너 재시작이 억제됨
- 인프라(Kafka/DB)가 Worker 2에 유지되어 있으므로 Pod 재배치 즉시(0s) 이루어짐
- 그러나 App Pod 재시작 반복이 남아 있어 10분 테스트 창 내 완전 복구 미달성
- 성공률 24.37% → 11.78%로 하락 — Probe 변경에 따른 초기 지연(initialDelaySeconds 90) 증가가 기동 시 트래픽 처리 공백 일부 영향

---

## Probe 개선 효과 분석

| 항목 | D-1 초기 | D-1 재테스트 | D-2 초기 | D-2 재테스트 |
|------|---------|------------|---------|------------|
| App Pod 재시작 횟수 | 7회 | 7회 (변화 없음) | 5회 | 3회 (40% 감소 ✅) |
| Detection Time | 52s | 56s | 46s | 47s |
| 성공률 | 18.02% | 6.21% | 24.37% | 11.78% |
| p95 응답시간 | 29.99s | 15,002ms | 29.99s | 15,005ms |

### 분석

**D-2 (App 노드 장애): Probe 개선 효과 있음**
- Liveness와 Readiness를 분리함으로써, 앱이 기동 중이거나 일시적으로 Readiness가 저하된 상황에서 Liveness Probe가 이를 실패로 오판하지 않음
- `failureThreshold 6` + `initialDelaySeconds 90` 설정으로 기동 초기 불안정 구간에서의 불필요한 컨테이너 Kill 방지
- 결과: 재시작 5회 → 3회 (40% 개선)

**D-1 (Infra 노드 장애): Probe 개선 효과 미미**
- Kafka/PostgreSQL이 함께 다운되는 상황에서는 App 컨테이너 자체가 DB/Kafka 연결 실패로 Readiness 뿐 아니라 Liveness 체크도 실패 가능
- Probe 분리만으로는 인프라 의존성으로 인한 재시작을 억제할 수 없음
- 근본 원인은 단일 Kafka 브로커 + 단일 레플리카 아키텍처

**전체 MTTR 목표 미달성의 근본 원인**
- 단일 Kafka 브로커: 인프라 노드 장애 시 메시지 큐 전체 불가 → 재연결 backoff 동안 대량 실패
- 단일 레플리카 구성: Pod 재시작 중 서비스 공백 발생
- Probe 튜닝은 증상 완화에 기여하나, 아키텍처 수준의 개선 없이는 목표 달성 불가

---

## 데이터 영속성 검증

### D-1 기준 발급 건수

| 항목 | 값 | 비고 |
|------|-----|------|
| 쿠폰 발급 성공 응답 | 9,861건 | k6 HTTP 200 기준 |
| 중복 발급 감지 (409) | 1,931건 | 멱등성 동작 |
| Kafka PV/PVC | 유지 | Worker 2 재시작 후 데이터 유지 확인 |
| 유실 건수 | 측정 불가 (재연결 지연으로 정확한 비교 불가) | 목표: 0건 |

### D-2 기준 발급 건수

| 항목 | 값 | 비고 |
|------|-----|------|
| 쿠폰 발급 성공 응답 | 9,854건 | k6 HTTP 200 기준 |
| 중복 발급 감지 (409) | 2,824건 | 멱등성 동작 (재시도 증가 반영) |

---

## Spring Boot 자동 재연결 결과

| 컴포넌트 | 재연결 메커니즘 | 결과 | 비고 |
|----------|---------------|------|------|
| PostgreSQL (HikariCP) | connection-test-query: SELECT 1 | 지연 재연결 — 재연결 완료 전 connection reset 에러 지속 | validation-timeout: 3s |
| Redis (Lettuce) | 자동 재연결 (기본 활성화) | D-2에서 1회 재시작 후 재연결 | shutdown-timeout: 200ms |
| Kafka Consumer | rebalance + reconnect.backoff | D-1에서 재연결 backoff 동안 대량 실패 | backoff: 1s → 10s |

---

## MTTR 종합

### 초기 실험

| 지표 | D-1 (Infra 노드 — Worker 2) | D-2 (App 노드 — Worker 1) | 목표 |
|------|-------------|-----------|------|
| Detection Time | 52s | 46s | < 1분 ✅ |
| Node Recovery | 5s | 6s | — |
| Pod Recovery | 66s | 0s (즉시) | — |
| Service Restoration | 미달성 (> 10min) | 미달성 (> 10min) | < 8분 ❌ |
| **Total MTTR** | **> 10분** | **> 10분** | **< 10분 ❌** |

### 재테스트 (Probe 개선 후)

| 지표 | D-1 재테스트 | D-2 재테스트 | 목표 |
|------|------------|------------|------|
| Detection Time | 56s | 47s | < 1분 ✅ |
| Node Recovery | 6s | 5s | — |
| Pod Recovery | 10s | 0s (즉시) | — |
| App Pod 재시작 횟수 | 7회 (변화 없음) | 3회 (5회 → 40% 감소) | — |
| Service Restoration | 미달성 (> 10min) | 미달성 (> 10min) | < 8분 ❌ |
| **Total MTTR** | **> 10분** | **> 10분** | **< 10분 ❌** |

---

## 결론

### 핵심 발견

1. **Detection Time은 목표 달성**: 모든 실험에서 노드 NotReady 전환이 1분 이내(46~56s)로 탐지 목표 충족
2. **Probe 개선은 D-2에서 효과적**: App 노드 장애 시 Liveness/Readiness Probe 분리로 App Pod 재시작 5회 → 3회(40% 감소)
3. **Probe 개선은 D-1에서 효과 미미**: Infra 노드 장애 시 Kafka/DB가 함께 다운되므로 Probe 분리만으로는 재시작 억제 불가
4. **인프라 노드 장애(D-1)가 더 심각**: Kafka/PostgreSQL 유실 시 Spring Boot의 재연결 지연(connection reset)으로 인프라 복구 후에도 에러가 지속되어 성공률 18%에 그침
5. **10분 테스트 창 내 완전 복구 미달성**: 모든 실험에서 TPS 정상 회복이 테스트 종료 시까지 이루어지지 않아 MTTR 목표(< 10분) 미충족
6. **멱등성 동작 확인**: 중복 발급 요청에 대해 409 응답이 정상 반환됨 (D-1: 1,931건, D-2: 2,824건)
7. **근본 한계**: 단일 Kafka 브로커 + 단일 레플리카 아키텍처가 MTTR 목표 미달성의 구조적 원인

### 개선 제안

1. **Liveness/Readiness Probe 분리** ✅ 적용 완료 — D-2에서 App Pod 재시작 40% 감소 확인
2. **Spring Boot 재연결 설정 강화**: HikariCP의 `initializationFailTimeout`, `connectionTimeout` 단축 및 Kafka `reconnect.backoff.max.ms` 조정으로 재연결 시간 단축
3. **Pod Anti-Affinity 적용**: App과 Kafka/DB를 서로 다른 노드에 강제 분리하여 단일 노드 장애 시 영향 범위 최소화
4. **Circuit Breaker 도입**: Resilience4j 등을 통해 인프라 장애 시 즉시 fallback 처리로 타임아웃 대기 대신 빠른 실패 응답 제공
5. **Kafka 다중 브로커 구성**: 단일 브로커 구성 탈피로 Kafka 노드 장애 시 가용성 확보 — MTTR 목표 달성을 위한 핵심 과제
