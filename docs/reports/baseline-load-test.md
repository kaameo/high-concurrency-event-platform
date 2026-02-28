# Baseline 부하 테스트 결과 — Phase 1 DB 직결 방식

- **실행일**: 2026-02-28
- **환경**: macOS (Apple Silicon), Docker Compose, Spring Boot 4.0.3 + Java 21 Virtual Threads
- **도구**: k6 v1.6.1
- **테스트 스크립트**: `k6/baseline-load-test.js`

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
| **총 요청 수** | ~127,000건 (4분) | — | — |
| **평균 RPS** | ~530 RPS | 100k TPS | ❌ (0.53%) |
| **최대 VUs** | 100 | — | — |
| **실제 발급 수** | **100,004건** | 100,000건 | ❌ 초과 발급 |
| **초과 발급** | **4건** | 0건 | ❌ Race Condition |
| **중복 사용자** | 0건 | 0건 | ✅ UNIQUE 제약 정상 |

---

## Race Condition 실증

```
totalStock      = 100,000
actual issued   = 100,004
over-issuance   = 4건 (0.004%)
```

### 원인 분석
```java
// CouponIssueService.java — check-then-act 패턴
long issuedCount = couponIssueRepository.countByCouponEventId(eventId);  // READ
if (issuedCount >= event.getTotalStock())                                 // CHECK
    throw new CouponSoldOutException(...);
couponIssueRepository.save(issue);                                        // ACT
```

100 VU 동시 요청 시 여러 스레드가 동일한 `issuedCount` (예: 99,999)를 읽고 모두 재고 체크를 통과하여 초과 발급 발생. `@Transactional`의 기본 `READ COMMITTED` 격리 수준에서는 이를 방지할 수 없음.

### 해결 방향
| 방식 | Phase | 효과 |
|------|-------|------|
| Redis `DECR` 원자적 차감 | Phase 2 | O(1) 원자적, 초과 발급 완전 방지 |
| `SELECT FOR UPDATE` | Phase 1 임시 | 행 수준 직렬화, 병목 발생 |

---

## 성능 분석

### DB 직결 방식의 한계
- **530 RPS** → PRD 목표 100k TPS의 **0.53%**
- 병목 원인:
  1. DB 트랜잭션 직렬화 (count → check → insert)
  2. HikariCP 기본 커넥션 풀 10개
  3. 동기 처리로 인한 스레드 블로킹 (Virtual Threads 사용에도 DB I/O 대기)

### Phase 2 개선 기대치
| 구간 | Phase 1 (현재) | Phase 2 (예상) |
|------|---------------|---------------|
| 재고 확인 | DB COUNT 쿼리 (~2ms) | Redis DECR (~0.1ms) |
| 발급 처리 | DB INSERT 동기 (~3ms) | Kafka Produce 비동기 (~1ms) |
| 예상 RPS | ~530 | ~5,000~10,000 |

---

## 실험 A 대조군 데이터

이 결과는 PRD 실험 A ("DB 직결 vs Kafka 버퍼링 성능 비교")의 **대조군(baseline)**으로 사용됩니다.

| 메트릭 | Baseline (DB 직결) | Kafka 버퍼링 (Phase 2) |
|--------|-------------------|----------------------|
| RPS | ~530 | 측정 예정 |
| Latency p95 | 측정됨 | 측정 예정 |
| Latency p99 | 측정됨 | 측정 예정 |
| Data Consistency | ❌ 초과 발급 4건 | 0건 목표 |

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
  'k6 Baseline Test Coupon',
  100000, 'ACTIVE',
  NOW() - INTERVAL '1 hour',
  NOW() + INTERVAL '24 hours',
  NOW(), NOW()
) ON CONFLICT (id) DO NOTHING;
"

# 4. k6 실행
k6 run k6/baseline-load-test.js

# 5. 결과 확인
PGPASSWORD=postgres psql -h localhost -U postgres -d event_platform -c "
SELECT COUNT(*) as total_issued FROM coupon_issue;
"
```
