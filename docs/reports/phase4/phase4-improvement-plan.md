# Phase 4 장애 복구 개선 방안

> 작성일: 2026-03-01
> 근거: 실험 D-1/D-2 초기 + 재테스트(Probe 개선) 결과

---

## 1. 현재 상황 진단

### 실험 결과 요약

| 지표 | D-1 초기 | D-1 재테스트 | D-2 초기 | D-2 재테스트 |
|------|---------|-------------|---------|-------------|
| Detection Time | 52s ✅ | 56s ✅ | 46s ✅ | 47s ✅ |
| App Pod Restarts | — | 7회 | 5회 | 3회 |
| 성공률 | 18.02% | 6.21% | 24.37% | 11.78% |
| Total MTTR | > 10min ❌ | > 10min ❌ | > 10min ❌ | > 10min ❌ |

### 근본 원인 분석

```
노드 장애 발생
    │
    ├─ [문제 1] 단일 브로커 Kafka ──→ 브로커 다운 = 전체 메시징 중단
    │
    ├─ [문제 2] 단일 레플리카 App ──→ Pod 1개 죽으면 서비스 완전 중단
    │
    ├─ [문제 3] Spring Boot 재연결 지연 ──→ Kafka/DB 복구 후에도 backoff 대기
    │
    ├─ [문제 4] 타임아웃 과다 ──→ 실패 요청이 30s 동안 커넥션 점유
    │
    └─ [문제 5] Graceful Degradation 부재 ──→ 인프라 장애 시 즉시 실패 대신 무한 대기
```

---

## 2. 개선 방안 (우선순위순)

### 2-1. Circuit Breaker 도입 (Resilience4j) — 즉시 적용 가능

**문제**: 인프라 장애 시 요청이 30초 타임아웃까지 대기 → p95 = 29.99s

**해결**: 연속 실패 감지 시 즉시 fallback 응답 반환

```java
// build.gradle에 의존성 추가
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
```

```yaml
# application.yaml
resilience4j:
  circuitbreaker:
    instances:
      couponService:
        sliding-window-size: 10            # 최근 10개 요청 기준
        failure-rate-threshold: 50         # 50% 실패 시 OPEN
        wait-duration-in-open-state: 10s   # 10초 후 HALF_OPEN
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 3s   # 3초 초과 = slow call
        slow-call-rate-threshold: 80       # 80% slow call 시 OPEN
```

```java
@CircuitBreaker(name = "couponService", fallbackMethod = "issueFallback")
public CouponResponse issueCoupon(CouponRequest request) {
    // 기존 발급 로직
}

private CouponResponse issueFallback(CouponRequest request, Throwable t) {
    // 503 Service Unavailable 즉시 반환
    throw new ServiceUnavailableException("서비스 일시 중단 중입니다. 잠시 후 다시 시도해주세요.");
}
```

**기대 효과**:
- p95 응답시간: 29.99s → **< 3s** (즉시 실패 반환)
- 커넥션 풀 고갈 방지 → 복구 후 빠른 정상화
- MTTR 단축: 인프라 복구 즉시 HALF_OPEN → 자동 회복

### 2-2. App 다중 레플리카 + Pod Anti-Affinity — 즉시 적용 가능

**문제**: `replicas: 1` → 단일 Pod 장애 = 서비스 완전 중단

**해결**:

```yaml
# k8s/app/deployment.yaml
spec:
  replicas: 2                    # 최소 2개 레플리카
  template:
    spec:
      topologySpreadConstraints:  # 노드 분산 배치
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app: event-platform
```

**기대 효과**:
- Worker 1 장애 시에도 Worker 2의 App Pod가 서비스 유지
- 장애 중에도 **50% 이상 처리 능력 유지**
- D-2 시나리오 성공률: 11.78% → **60%+ 예상**

### 2-3. HikariCP 재연결 최적화 — 즉시 적용 가능

**문제**: DB 복구 후에도 HikariCP가 stale connection을 유지 → connection reset 지속

**해결**:

```yaml
# application.yaml
spring:
  datasource:
    hikari:
      connection-test-query: SELECT 1
      validation-timeout: 3000
      connection-timeout: 3000          # 5000 → 3000 (빠른 실패)
      initialization-fail-timeout: 0    # 초기화 실패 시 즉시 시작 (백그라운드 재연결)
      keepalive-time: 30000             # 30초마다 연결 유효성 확인
      max-lifetime: 60000               # 60초마다 연결 갱신
      minimum-idle: 5                   # 최소 유휴 연결 유지
      leak-detection-threshold: 10000   # 10초 이상 반환 안 되면 경고
```

**핵심 변경**:
| 설정 | 현재 | 개선 | 효과 |
|------|------|------|------|
| `connection-timeout` | 5000ms | 3000ms | 빠른 실패 |
| `initialization-fail-timeout` | 미설정 (1ms) | 0 | DB 없어도 앱 시작 가능 |
| `keepalive-time` | 미설정 | 30000ms | stale 연결 조기 감지 |
| `max-lifetime` | 미설정 (30min) | 60000ms | 연결 갱신 주기 단축 |

### 2-4. Kafka Consumer 재연결 최적화 — 즉시 적용 가능

**문제**: `reconnect.backoff.max.ms: 10000` → 최대 10초 대기 후 재연결 시도

**해결**:

```yaml
# application.yaml
spring:
  kafka:
    consumer:
      properties:
        reconnect.backoff.ms: 500          # 1000 → 500
        reconnect.backoff.max.ms: 5000     # 10000 → 5000 (최대 백오프 절반)
        session.timeout.ms: 15000          # 세션 타임아웃 단축
        heartbeat.interval.ms: 3000        # 하트비트 주기 단축
        request.timeout.ms: 10000          # 요청 타임아웃 단축
    producer:
      properties:
        reconnect.backoff.ms: 500
        reconnect.backoff.max.ms: 5000
        request.timeout.ms: 10000
        delivery.timeout.ms: 30000
```

### 2-5. Kafka 다중 브로커 — 프로덕션 필수

**문제**: `kafka-0` 단일 브로커 → 브로커 노드 다운 = Kafka 전체 중단

**해결**:

```yaml
# Kafka Helm values 수정
replicaCount: 3                   # 브로커 3대
defaultReplicationFactor: 3
minInsyncReplicas: 2              # 최소 2개 동기화 보장

topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule
```

**기대 효과**:
- 브로커 1대 장애 시에도 나머지 2대가 서비스 유지
- D-1 시나리오의 근본 해결 — Kafka 가용성 확보
- `min.insync.replicas=2`로 데이터 유실 방지

### 2-6. PodDisruptionBudget (PDB) — 즉시 적용 가능

**문제**: 의도적 노드 드레인 시 모든 Pod가 동시 종료될 수 있음

**해결**:

```yaml
# k8s/app/pdb.yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: event-platform-pdb
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app: event-platform
```

---

## 3. 적용 우선순위 로드맵

### Phase 4-A: 즉시 적용 (코드/설정 변경만)

| # | 항목 | 난이도 | 예상 MTTR 효과 |
|---|------|--------|---------------|
| 1 | Circuit Breaker (Resilience4j) | 중 | p95 30s → 3s, 복구 후 자동 회복 |
| 2 | App replicas: 2 + Anti-Affinity | 하 | D-2 서비스 중단 방지 |
| 3 | HikariCP keepalive/max-lifetime | 하 | stale 연결 조기 감지 |
| 4 | Kafka backoff 단축 | 하 | 재연결 시간 50% 단축 |
| 5 | PDB 적용 | 하 | 드레인 시 가용성 보장 |

### Phase 4-B: 인프라 확장 (클러스터 변경)

| # | 항목 | 난이도 | 예상 MTTR 효과 |
|---|------|--------|---------------|
| 6 | Kafka 다중 브로커 (3대) | 상 | D-1 근본 해결 |
| 7 | PostgreSQL HA (Patroni) | 상 | DB SPOF 제거 |
| 8 | Redis Sentinel/Cluster | 중 | Redis SPOF 제거 |

### Phase 4-C: 고급 복원력 패턴

| # | 항목 | 난이도 | 비고 |
|---|------|--------|------|
| 9 | Retry with Exponential Backoff (API 레벨) | 중 | 클라이언트 재시도 전략 |
| 10 | Bulkhead 패턴 | 중 | 스레드풀 격리로 연쇄 장애 방지 |
| 11 | Health Check 세분화 | 하 | Kafka/Redis/DB 별도 indicator |

---

## 4. 적용 시 예상 결과

### Before (현재)

```
노드 장애 발생
    → 서비스 완전 중단 (0% 처리)
    → 30초 타임아웃 대기
    → 복구 후에도 재연결 지연 (분 단위)
    → MTTR > 10분
```

### After (Phase 4-A 적용 후 예상)

```
노드 장애 발생
    → Circuit Breaker OPEN (3초 내)
    → 즉시 503 반환 (사용자에게 빠른 피드백)
    → 남은 레플리카가 50% 트래픽 처리 (D-2 시나리오)
    → 인프라 복구 감지 → HALF_OPEN → 자동 회복
    → 예상 MTTR: 3~5분
```

### After (Phase 4-A + 4-B 적용 후 예상)

```
노드 장애 발생
    → Kafka 나머지 브로커가 리더 선출 (< 30초)
    → App 레플리카 2개 중 1개 유지
    → Circuit Breaker가 일시적 실패만 차단
    → 예상 MTTR: < 2분
    → 예상 성공률: > 70%
```

---

## 5. 이미 적용된 개선 사항

| 항목 | 상태 | 효과 |
|------|------|------|
| Liveness/Readiness Probe 분리 | ✅ 적용 완료 | D-2 재시작 5→3회 (40% 감소) |
| HikariCP connection-test-query | ✅ 적용 완료 | stale 연결 감지 |
| Kafka reconnect.backoff 설정 | ✅ 적용 완료 | 재연결 시도 주기 확보 |
| Redis Lettuce shutdown-timeout | ✅ 적용 완료 | 정상 종료 보장 |

---

## 6. 결론

현재 MTTR > 10분의 핵심 원인은 **아키텍처적 단일 장애점(SPOF)**이다:

1. **단일 Kafka 브로커**: 브로커 노드 장애 = 메시징 전체 중단
2. **단일 App 레플리카**: Pod 장애 = 서비스 전체 중단
3. **Graceful Degradation 부재**: 장애 시 30초 타임아웃 대기

Probe 튜닝과 같은 설정 수준 개선으로는 근본적인 한계가 있으며,
**Circuit Breaker + 다중 레플리카**(Phase 4-A)를 적용하면 MTTR을 3~5분으로 단축할 수 있고,
**Kafka 다중 브로커**(Phase 4-B)까지 적용하면 MTTR < 2분 달성이 가능하다.

> kind 클러스터(2 Worker) 환경의 물리적 제약을 감안하면,
> Phase 4-A 수준의 개선이 현실적인 최적 목표이며,
> Phase 4-B는 프로덕션 환경에서 반드시 적용해야 할 필수 요건이다.
