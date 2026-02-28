# Phase 2: 핵심 로직 구현 (3~4주)

## 2.1 Redis 재고 관리

### RedisStockManager — Atomic Counter 기반 재고 차감
```
동작 흐름:
1. 이벤트 시작 시: Redis에 coupon:{couponEventId}:stock = 100000 SET
2. 발급 요청 시: DECR coupon:{couponEventId}:stock
   - 결과 >= 0 → 발급 가능 (Kafka로 전달)
   - 결과 < 0  → 재고 소진 (INCR로 복원 후 거부)
```

**Redis Key 설계:**
| Key | Type | 용도 |
|-----|------|------|
| `coupon:{id}:stock` | String (Integer) | 잔여 재고 수량 |
| `coupon:{id}:issued:{userId}` | String | 중복 발급 방지 (SET NX, TTL 24h) |
| `idempotency:{key}` | String (requestId) | Idempotency-Key 저장 (SET NX, TTL 24h) |
| `rate_limit:{userId}` | String (Integer) | Rate Limiting 카운터 |

### RedisRateLimiter — Sliding Window
- 알고리즘: Sliding Window Counter (Redis ZSET 기반)
- 제한: 사용자당 초당 5회
- Key: `rate_limit:{userId}`, Score: timestamp, TTL: 1초

### RedisDistributedLock — Redisson
- 용도: 재고 초기화/동기화 시 사용 (정상 발급 흐름에서는 DECR의 원자성으로 충분)
- Lock Key: `lock:coupon:{couponEventId}:init`
- waitTime: 3초, leaseTime: 5초

### 재고 동기화
- DB → Redis 초기 로딩: 이벤트 시작 전 Scheduler 또는 Admin API로 실행
- Redis → DB 정합성 검증: Spring Batch Job으로 주기적 비교

---

## 2.2 Kafka 비동기 처리

### Topic 설계
| Topic | Partitions | Replication | 용도 |
|-------|-----------|-------------|------|
| `coupon-issue` | 1 → 10 (실험 C) | 1 (로컬) | 발급 요청 |
| `coupon.issue.dlq` | 1 | 1 | 실패 메시지 |

### CouponIssueMessage
```java
public record CouponIssueMessage(
    String requestId,        // UUID - 추적 키 & Kafka Message Key
    Long couponEventId,
    Long userId,
    String idempotencyKey,   // 클라이언트 멱등성 키
    String traceId,          // 분산 추적용
    LocalDateTime requestedAt
) {}
```

### Producer 구현
```
CouponIssueService.issue()
  1. Rate Limit 확인 (RedisRateLimiter)
  2. 중복 발급 확인 (Redis SET NX)
  3. 재고 차감 (Redis DECR)
  4. Kafka 발행 (CouponIssueProducer.send)
  5. 202 Accepted 응답 반환
```

**Producer 설정:**
- `acks=all`: 모든 replica 확인 후 응답
- `retries=3`: 재시도
- `enable.idempotence=true`: 중복 전송 방지
- `max.in.flight.requests.per.connection=5`

### Consumer 구현
```
CouponIssueConsumer.consume(CouponIssueMessage)
  1. DB에 coupon_issue 레코드 저장 (status=COMPLETED)
  2. 실패 시 → DLQ로 전송, status=FAILED 업데이트
  3. 성공/실패 메트릭 기록
```

**Consumer 설정:**
- `enable.auto.commit=false`: 수동 커밋
- `max.poll.records=500`: 배치 처리
- `isolation.level=read_committed`

### 에러 핸들링
```
실패 시나리오:
1. DB 저장 실패 → 3회 재시도 (지수 백오프: 1s, 2s, 4s) 후 DLQ 전송
2. 역직렬화 실패 → DLQ 즉시 전송 (재시도 불가)
3. Consumer 타임아웃 → rebalance 후 다른 Consumer가 처리
```

- `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
- DLQ 토픽: `coupon.issue.dlq`
- 최대 재시도: 3회, 지수 백오프 (1s → 2s → 4s)
- 재시도 초과 시 DLQ 적재 + 운영자 수동 재처리 절차

### Idempotency 보장
- `Idempotency-Key` → Redis `SET NX` (TTL 24h)로 중복 요청 차단
- DB `idempotency_key` UNIQUE 제약으로 최종 방어
- `requestId` UNIQUE 제약으로 Kafka 재전송 시 중복 저장 방지

---

## 2.3 데이터 계층

### QueryDSL 동적 쿼리
`CouponIssueQueryRepository` 구현:

```java
// 발급 내역 조회 (필터: couponEventId, userId, status, 기간)
public Page<CouponIssue> searchIssues(CouponIssueSearchCondition condition, Pageable pageable)

// 쿠폰별 발급 통계
public CouponIssueStats getIssueStats(Long couponEventId)
```

**CouponIssueSearchCondition:**
```java
public record CouponIssueSearchCondition(
    Long couponEventId,
    Long userId,
    IssueStatus status,
    LocalDateTime startDate,
    LocalDateTime endDate
) {}
```

### Spring Batch Job

#### CouponSettlementJob
- **목적:** Redis ↔ DB 재고 정합성 검증 + 발급 통계 집계
- **스케줄:** 매일 02:00 (이벤트 종료 후)
- **Step 1:** Redis 잔여 재고 vs DB 발급 건수 비교 → 불일치 시 알림
- **Step 2:** 쿠폰별 발급 통계 집계 → 리포트 테이블 저장

```
Reader: JpaPagingItemReader (coupon_issue 테이블)
Processor: 통계 집계 로직
Writer: JpaItemWriter (settlement_report 테이블)
Chunk size: 1000
```

---

## 2.4 Virtual Threads 적용

### 설정
```yaml
spring:
  threads:
    virtual:
      enabled: true  # Tomcat이 Virtual Threads 사용
```

### 검증 포인트
- [ ] 동시 요청 1,000개에서 Platform Thread vs Virtual Thread 비교
- [ ] Redis/Kafka I/O에서 Virtual Thread의 blocking 동작 확인
- [ ] Redisson 분산 락과 Virtual Thread 호환성 확인
- [ ] Thread dump로 Virtual Thread 생성 확인

---

## Phase 2 완료 기준
- [ ] 쿠폰 발급 E2E 동작: API 요청 → Redis 차감 → Kafka → DB 저장
- [ ] 재고 정확성: 동시 1,000건 발급 시 초과 발급 0건
- [ ] 중복 발급 방지 동작 확인
- [ ] Rate Limiter 동작 확인
- [ ] DLQ 정상 동작 확인
- [ ] QueryDSL 조회 테스트 통과
- [ ] Spring Batch Job 수동 실행 성공
- [ ] Virtual Threads 활성화 확인 (Thread dump)
