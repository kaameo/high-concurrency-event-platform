# ADR-009: 장애 복구 전략 및 MTTR 최적화

## 상태

Accepted

## 날짜

2026-03-01

## 컨텍스트

Phase 3에서 HPA 기반 Auto-scaling을 검증한 후, 노드 장애 시 서비스 복구 능력을 실증할 필요가 있다.
kind 클러스터(CP1 + Worker2) 환경에서 인프라 노드 및 App 노드 장애 시나리오를 실험하여 MTTR을 측정하고, 데이터 영속성을 검증한다.

### 문제

1. 노드 장애 시 Pod Eviction 및 재배치에 소요되는 시간이 서비스 가용성에 직접 영향
2. Kafka, Redis, PostgreSQL 등 Stateful 인프라의 장애 시 데이터 유실 위험
3. Spring Boot 애플리케이션의 자동 재연결 능력 미검증

## 결정

### 1. Kubernetes 기본 장애 복구 메커니즘 활용

- `pod-eviction-timeout` (기본 5분) 기반 Pod 재배치
- PV/PVC를 통한 Stateful 데이터 영속성 보장
- Node Readiness Probe를 통한 자동 감지

### 2. Spring Boot Reconnection 설정 강화

```yaml
# HikariCP: 연결 유효성 검증
spring.datasource.hikari:
  connection-test-query: SELECT 1
  validation-timeout: 3000
  connection-timeout: 5000

# Redis Lettuce: 자동 재연결 (기본 활성화)
spring.data.redis.lettuce:
  shutdown-timeout: 200ms

# Kafka Consumer: 지수 백오프 재연결
spring.kafka.consumer.properties:
  reconnect.backoff.ms: 1000
  reconnect.backoff.max.ms: 10000
```

### 3. MTTR 목표 설정

| 지표 | 목표 |
|------|------|
| Detection Time (노드 종료 → NotReady) | < 1분 |
| Recovery Time (NotReady → Pod Running) | < 6분 |
| Service Restoration (Pod Running → TPS 정상) | < 8분 |
| Total MTTR | < 10분 |

## 실험 결과

### D-1: Infra 노드 장애 (Worker 2 — Kafka/PostgreSQL)
- Detection Time: 52초 (✅ < 1분)
- Node Recovery: 5초 (docker start → Ready)
- Pod Recovery: 66초 (docker start → Running)
- App Pod 재시작 횟수: 7회
- Service Restoration: **미달성** — Kafka/DB 재연결 지연으로 connection reset 지속
- 성공률: 18.02% (134,261 requests, 110,067 errors)

### D-2: App 노드 장애 (Worker 1 — App/Redis)
- Detection Time: 46초 (✅ < 1분)
- Node Recovery: 6초 (docker start → Ready)
- Pod Recovery: 0초 (즉시, 동일 노드 재배치)
- App Pod 재시작 횟수: 5회
- Service Restoration: **미달성** — App Pod 5회 재시작으로 안정화 지연
- 성공률: 24.37% (146,841 requests, 111,053 errors)

### 데이터 영속성
- Kafka PV/PVC 데이터 유지 확인 (Worker 2 재시작 후)
- 멱등성 동작 확인: 중복 발급 409 응답 정상 (D-1: 1,931건, D-2: 2,824건)

### 재테스트 (Probe 개선 후)

적용 변경사항: Liveness/Readiness Probe 분리
- Liveness: `/actuator/health/liveness`, timeout 5s, failureThreshold 6, initialDelaySeconds 90
- Readiness: `/actuator/health/readiness`

**D-1 재테스트 (Infra 노드 장애 — Worker 2):**
- Detection Time: 56초 (✅ < 1분)
- Node Recovery: 6초 (docker start → Ready)
- Pod Recovery: 10초 (docker start → Running)
- App Pod 재시작 횟수: 7회 (변화 없음 — Kafka/DB 다운으로 Probe 분리 효과 없음)
- Service Restoration: **미달성**
- 성공률: 6.21% (122,272 requests, 114,673 errors) — 재고 소진 0건 (Kafka 연결 실패로 발급 미처리)

**D-2 재테스트 (App 노드 장애 — Worker 1):**
- Detection Time: 47초 (✅ < 1분)
- Node Recovery: 5초 (docker start → Ready)
- Pod Recovery: 0초 (즉시)
- App Pod 재시작 횟수: 3회 (5회 → **40% 감소 ✅**)
- Service Restoration: **미달성**
- 성공률: 11.78% (129,884 requests, 114,582 errors)

**Probe 개선 효과 요약:**
- D-2(App 노드 장애)에서 Liveness/Readiness 분리로 App Pod 재시작 40% 감소 — 실질적 개선 효과 확인
- D-1(Infra 노드 장애)에서는 Kafka/DB가 함께 다운되므로 Probe 분리만으로 재시작 억제 불가 — 인프라 의존성이 근본 원인
- 전체 MTTR 목표 미달성의 구조적 원인: 단일 Kafka 브로커 + 단일 레플리카 아키텍처

## 대안 검토

### Pod Disruption Budget (PDB)
- 장점: 의도적 노드 드레인 시 최소 가용 Pod 보장
- 단점: 비의도적 장애(노드 크래시)에는 적용 불가
- 결론: 향후 프로덕션 환경에서 추가 고려

### Pod Anti-Affinity
- 장점: Kafka 브로커 복수 배치 시 노드 분산 보장
- 단점: 현재 단일 브로커 환경에서는 불필요
- 결론: 브로커 확장 시 적용

### StatefulSet + Headless Service
- 장점: 안정적인 네트워크 ID 및 순차적 롤링 업데이트
- 단점: 현재 Kafka는 이미 StatefulSet으로 배포
- 결론: 현 구조 유지

## 결과

- **Detection Time 목표 달성**: 모든 실험(초기 + 재테스트)에서 1분 이내 (46~56초)
- **Probe 개선 부분 효과**: D-2(App 노드 장애)에서 App Pod 재시작 5회 → 3회(40% 감소) — Liveness/Readiness 분리의 실질적 효과 확인
- **MTTR 목표 미달성**: 10분 내 완전한 서비스 복구 미달성 — Probe 개선 후에도 구조적 한계 잔존
- **개선 사항**:
  1. ✅ **적용 완료** — Liveness/Readiness Probe 분리 (liveness: `/actuator/health/liveness`, failureThreshold 6, initialDelaySeconds 90)
  2. ✅ **적용 완료** — HikariCP 재연결 최적화 (`connectionTimeout` 3s, `keepaliveTime` 30s, `maxLifetime` 60s, `initializationFailTimeout` 0)
  3. ✅ **적용 완료** — Kafka Producer/Consumer 재연결 최적화 (`reconnect.backoff` 500/5000ms, `session.timeout` 15s, `heartbeat` 3s)
  4. ✅ **적용 완료** — Resilience4j Circuit Breaker 도입 (`couponIssue` 인스턴스: sliding-window 10, failure-rate 50%, wait-duration 10s, slow-call 3s/80%) — 장애 시 30s 타임아웃 대기 → 즉시 503 반환
  5. ✅ **적용 완료** — App 다중 레플리카 (replicas 2) + `topologySpreadConstraints` 노드 분산 배치 — Worker 1 장애 시에도 Worker 2의 Pod가 서비스 유지
  6. **Kafka 다중 브로커 구성** — MTTR 목표 달성을 위한 핵심 과제 (단일 브로커가 근본 원인)
