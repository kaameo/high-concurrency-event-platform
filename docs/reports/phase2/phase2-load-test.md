# Phase 2 부하 테스트 결과 — Redis + Kafka 비동기 파이프라인

- **실행일**: 2026-02-28
- **환경**: macOS (Apple Silicon), Docker Compose, Spring Boot 4.0.3 + Java 21 Virtual Threads
- **도구**: k6 v1.6.1
- **테스트 스크립트**: `k6/phase2-load-test.js`

---

## 테스트 시나리오

| 단계 | 시간 | VUs | 목적 |
|------|------|-----|------|
| Warm-up | 0~30s | 0→10 | 워밍업 |
| Ramp-up | 30~60s | 10→50 | 점진적 부하 |
| Sustained | 60~120s | 50 | 안정 부하 |
| Peak ramp | 120~150s | 50→100 | 피크 진입 |
| Sustained peak | 150~210s | 100 | 피크 유지 |
| Cool-down | 210~240s | 100→0 | 종료 |

### 테스트 데이터
- CouponEvent: `totalStock = 100,000`
- userId: 1~10,000,000 랜덤 생성
- Idempotency-Key: 매 요청마다 UUID v4 생성

---

## 핵심 결과

| 지표 | 값 | PRD 목표 | 판정 |
|------|-----|----------|------|
| **총 요청 수** | 130,408건 (4분) | — | — |
| **평균 RPS** | 543 RPS | 100k TPS | 개선 중 (Phase 3에서 튜닝) |
| **최대 VUs** | 100 | — | — |
| **실제 발급 수** | **100,000건** | 100,000건 | **정확히 일치** |
| **초과 발급** | **0건** | 0건 | **해결** |
| **Redis 잔여 재고** | 0 | 0 | **DB와 정합** |
| **중복 거부** | 2,547건 | — | 정상 동작 |
| **재고 소진 거부** | 27,861건 | — | 정상 동작 |
| **에러** | 0건 | — | — |
| **성공률** | 100% | >95% | **통과** |

---

## Baseline 대비 비교 (실험 A)

| 메트릭 | Phase 1 (DB 직결) | Phase 2 (Redis+Kafka) | 변화 |
|--------|-------------------|----------------------|------|
| **총 요청** | ~127,000 | 130,408 | +2.7% |
| **평균 RPS** | ~530 | 543 | +2.5% |
| **실제 발급 수** | 100,004 | **100,000** | **정확** |
| **초과 발급** | **4건** | **0건** | **Race Condition 해결** |
| **Latency avg** | — | 5.44ms | — |
| **Latency p50** | — | 4.68ms | — |
| **Latency p90** | — | 9.36ms | — |
| **Latency p95** | — | 12.04ms | — |
| **Latency max** | — | 39.29ms | — |
| **Redis 정합성** | N/A | **DB = Redis** | **100%** |

### RPS 유사한 이유
- 동일한 VU 프로파일 (max 100 VU) + `sleep(0.1)` 제한
- Phase 2의 진정한 이점은 RPS가 아닌 **데이터 정합성** + **수평 확장 가능성**
- RPS 극대화는 Phase 3에서 VU 증가 + sleep 제거 + Kafka 파티션 튜닝으로 측정 예정

---

## 정합성 검증 결과

```
DB 발급 건수:       100,000건
Redis 잔여 재고:    0
Redis 차감 건수:    100,000건 (totalStock - remaining = 100,000 - 0)
DB = Redis:         일치
초과 발급:          0건
```

### Race Condition 해결 증거
- Phase 1: `countByCouponEventId()` (check) → `save()` (act) = **4건 초과 발급**
- Phase 2: `Redis DECR` (원자적 차감) = **0건 초과 발급**

---

## 응답 코드 분포

| HTTP Status | 의미 | 건수 | 비율 |
|-------------|------|------|------|
| 202 Accepted | 발급 성공 (PENDING) | 100,000 | 76.7% |
| 409 Conflict | 중복 발급 거부 | 2,547 | 2.0% |
| 410 Gone | 재고 소진 | 27,861 | 21.4% |
| 429 Too Many | Rate Limit | 0 | 0% |
| 5xx Error | 서버 에러 | 0 | 0% |

---

## 처리 파이프라인 성능

```
POST /api/v1/coupons/issue (avg 5.44ms)
  ├─ Redis: Idempotency SET NX     (~0.1ms)
  ├─ Redis: Rate Limit ZSET        (~0.1ms)
  ├─ Redis: Duplicate SET NX       (~0.1ms)
  ├─ Redis: Stock DECR             (~0.1ms)
  └─ Kafka: Producer send (async)  (~1ms)
  → 202 Accepted

Kafka Consumer (비동기, 백그라운드)
  └─ DB INSERT coupon_issue        (~3ms)
```

---

## Kafka 메시지 처리

- Topic: `coupon-issue` (3 partitions)
- 발행 건수: 100,000건
- 소비 건수: 100,000건 (DB 저장 완료)
- DLQ 전송: 0건

---

## 테스트 재현 방법

```bash
# 1. 인프라 기동
docker-compose up -d

# 2. Spring Boot 앱 시작
./gradlew bootRun

# 3. 시드 데이터 삽입
PGPASSWORD=postgres psql -h localhost -U postgres -d event_platform -c "
INSERT INTO coupon_event (id, name, total_stock, status, start_at, end_at, created_at, updated_at)
VALUES (
  '019577a0-0000-7000-8000-000000000001',
  'k6 Phase2 Test Coupon',
  100000, 'ACTIVE',
  NOW() - INTERVAL '1 hour',
  NOW() + INTERVAL '24 hours',
  NOW(), NOW()
) ON CONFLICT (id) DO UPDATE SET total_stock = 100000, status = 'ACTIVE';
"

# 4. 기존 발급 데이터 초기화
PGPASSWORD=postgres psql -h localhost -U postgres -d event_platform -c "DELETE FROM coupon_issue;"

# 5. Redis 재고 초기화
curl -X POST http://localhost:8080/api/v1/admin/coupons/019577a0-0000-7000-8000-000000000001/init-stock

# 6. k6 실행
k6 run k6/phase2-load-test.js

# 7. 결과 확인
PGPASSWORD=postgres psql -h localhost -U postgres -d event_platform -c "SELECT COUNT(*) as total_issued FROM coupon_issue;"
redis-cli GET coupon:stock:019577a0-0000-7000-8000-000000000001
```

---

## 결론

1. **데이터 정합성 목표 달성**: 초과 발급 0건, Redis ↔ DB 100% 정합
2. **Race Condition 완전 해결**: Redis DECR 원자적 차감으로 동시성 문제 제거
3. **안정적 비동기 파이프라인**: Kafka를 통한 DB 저장 분리, DLQ 에러 핸들링 구축
4. **RPS 극대화는 Phase 3 과제**: VU 확장, sleep 제거, Kafka 파티션/Consumer 튜닝 필요
