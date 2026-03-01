# 실험 C: Kafka 파티션 튜닝 — Consumer 병렬성과 처리량 관계 실증

## 실험 목적

파티션 수와 Consumer concurrency를 함께 늘릴 때 DB INSERT 처리량이 비례 증가하는지 실증한다.

## 실험 환경

| 항목 | 값 |
|------|-----|
| 인프라 | Docker Compose (Kafka, PostgreSQL, Redis) |
| 애플리케이션 | Spring Boot 3 + Virtual Threads |
| 엔드포인트 | `POST /api/v1/coupons/issue` (Kafka async) |
| 부하 패턴 | Spike (10→500→1000 VUs, 150s) |
| 쿠폰 이벤트 수량 | 100,000건 |

## 실험 구성

| 구성 | 파티션 수 | Consumer concurrency | Consumer 스레드 |
|------|-----------|---------------------|-----------------|
| C-1 | 1 | 1 | 1 |
| C-2 | 3 | 3 | 3 |
| C-3 | 10 | 10 | 10 |

## 실행 절차

각 구성별:
1. Kafka UI API로 토픽 삭제
2. Redis `FLUSHALL` + DB `TRUNCATE coupon_issue`
3. 환경변수 설정 후 앱 재시작 (`KAFKA_COUPON_PARTITIONS`, `KAFKA_COUPON_CONSUMER_CONCURRENCY`)
4. Redis 재고 초기화: `POST /api/v1/admin/coupons/{id}/init-stock`
5. k6 실행: `k6 run --env CONFIG=C{n} k6/phase3-experiment-c-partition.js`

## 결과

### C-1: 1 partition / concurrency 1

| 지표 | 값 |
|------|-----|
| Total Requests | 933,510 |
| RPS (avg) | 6,221 |
| Latency avg | 57.03ms |
| Latency p95 | 118.46ms |
| Latency p99 | 148.79ms |
| Latency max | 178.32ms |
| DB 발급 건수 | 100,000 |

### C-2: 3 partitions / concurrency 3

| 지표 | 값 |
|------|-----|
| Total Requests | 828,701 |
| RPS (avg) | 5,523 |
| Latency avg | 70.62ms |
| Latency p95 | 168.88ms |
| Latency p99 | 206.33ms |
| Latency max | 237.97ms |
| DB 발급 건수 | 100,000 |

### C-3: 10 partitions / concurrency 10

| 지표 | 값 |
|------|-----|
| Total Requests | 804,790 |
| RPS (avg) | 5,363 |
| Latency avg | 74.19ms |
| Latency p95 | 140.76ms |
| Latency p99 | 159.39ms |
| Latency max | 205.57ms |
| DB 발급 건수 | 100,000 |

## 비교 요약

| 지표 | C-1 (1/1) | C-2 (3/3) | C-3 (10/10) |
|------|-----------|-----------|-------------|
| RPS | **6,221** | 5,523 | 5,363 |
| Latency avg | **57.03ms** | 70.62ms | 74.19ms |
| p95 Latency | **118.46ms** | 168.88ms | 140.76ms |
| p99 Latency | **148.79ms** | 206.33ms | 159.39ms |
| DB 발급 건수 | 100,000 | 100,000 | 100,000 |
| Total Requests | 933,510 | 828,701 | 804,790 |

## 분석

### 관찰 사항

1. **Producer 측 (HTTP 응답)**: 파티션 수를 늘릴수록 RPS가 오히려 감소 (6,221 → 5,523 → 5,363). 파티션이 많아지면 Producer가 파티션 선택, 메타데이터 관리 등의 오버헤드가 증가하며, 단일 브로커 환경에서는 파티션 증가가 I/O 분산 효과 없이 오버헤드만 추가된다.

2. **Consumer 측 (DB INSERT)**: 3개 구성 모두 정확히 100,000건을 발급 완료. 그러나 Consumer 병렬성 증가가 **Producer 응답 시간에는 부정적 영향**을 미쳤다. 이는 Consumer 스레드 증가로 인한 DB connection pool 경합, Kafka rebalancing 오버헤드가 원인으로 추정된다.

3. **병목 지점 분석**:
   - **단일 브로커**: 파티션이 늘어나도 물리적 디스크가 1개이므로 I/O 병렬화 효과 없음
   - **HikariCP pool**: 기본 pool size(10)에서 Consumer 10개가 동시에 DB 커넥션을 요청하면 pool 경합 발생
   - **Kafka 메타데이터**: 파티션이 많을수록 Consumer Group 리밸런싱 비용 증가

### 결론

**단일 브로커 + 단일 DB 환경에서 파티션/concurrency 증가는 성능 향상에 기여하지 않았다.**

- C-1 (1 partition, 1 consumer)이 가장 높은 RPS와 가장 낮은 레이턴시를 기록
- 파티션 증가는 분산 브로커 환경에서만 효과적이며, 단일 브로커에서는 오버헤드만 추가
- Consumer 병렬성은 DB connection pool과 함께 튜닝해야 의미 있음 (현재 HikariCP 기본 pool size = 10)

### 핵심 인사이트

> **파티션 증가 ≠ 자동 성능 향상**. 브로커 수, DB connection pool, 디스크 I/O가 함께 확장되어야 병렬화 효과를 얻을 수 있다.

### 단일 머신 한계

본 실험은 모든 구성요소(Kafka, PostgreSQL, Redis, Application)가 **하나의 물리 머신** 위에서 Docker 컨테이너로 동작한다. 이 환경에서는:

- **CPU/디스크/메모리를 전부 공유** → 컨테이너를 아무리 늘려도 물리 자원은 동일
- 파티션/Consumer 증가 → 동일 디스크에 대한 I/O 경합만 증가
- 다중 브로커를 Docker로 추가해도 네트워크 오버헤드만 늘고 실제 분산 효과 없음
- **진정한 병렬화 효과는 물리적으로 분리된 다중 노드 환경에서만 기대 가능**

따라서 이 실험의 결론은 "파티션 튜닝이 무의미하다"가 아니라, **단일 머신 환경에서는 파티션 병렬화의 효과를 측정할 수 없다**는 것이다.

