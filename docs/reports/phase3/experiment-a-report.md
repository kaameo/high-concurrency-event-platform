# 실험 A 결과 리포트: DB 직결 vs Kafka 버퍼링

## 실험 환경
- **Hardware**: Apple Silicon Mac (로컬)
- **Runtime**: Spring Boot 4.0.3, Java 21, Virtual Threads
- **Infra**: Docker Compose (PostgreSQL 16, Redis 7, Kafka 4.1)
- **부하 도구**: k6 (Grafana)
- **재고**: 100,000장

## 실험 설계

### A-1: Kafka 비동기 (기존 구현)
```
Request → Redis 재고 DECR → Kafka Produce → 202 Accepted
         (Consumer가 비동기로 DB INSERT)
```

### A-2: DB 직결 동기
```
Request → Redis 재고 DECR → DB INSERT (동기) → 200 OK
```

두 방식 모두 동일한 Redis 재고 차감 + 멱등성 + 중복 체크 + Rate Limit 파이프라인 사용.

## 부하 조건 (동일)

| 항목 | 값 |
|------|-----|
| Spike 패턴 | 10 → 200 VU (5s) → 200 유지 (60s) → 500 VU (5s) → 500 유지 (60s) |
| Max VU | 500 |
| 테스트 시간 | 2분 30초 |
| 재고 | 100,000장 |

## 결과 (동일 500 VU)

| 항목 | Async (Kafka) | Sync (DB) | 비율 |
|------|--------------|-----------|------|
| **RPS (avg)** | **4,711/s** | **3,019/s** | **1.56x** |
| p50 Latency | **12.09ms** | 66.75ms | **5.5x 빠름** |
| p95 Latency | **46.98ms** | 96.19ms | **2.0x 빠름** |
| Max Latency | **86.25ms** | 359.27ms | **4.2x 빠름** |
| 성공률 | 100% | 100% | 동일 |
| DB 발급 건수 | 100,000 | 100,000 | 동일 |
| 재고 정합성 | 정확 (초과 0건) | 정확 (초과 0건) | 동일 |
| 품절 응답 | 583,040건 | 342,886건 | — |
| 중복 거부 | 23,716건 | 9,989건 | — |

## 분석

### 1. 처리량 (Throughput)
- 동일 500 VU에서 Async가 **1.56배 높은 RPS** (4,711 vs 3,019)
- Async: Kafka produce 후 즉시 202 반환 → 스레드 빠르게 해제 → 더 많은 요청 처리
- Sync: DB INSERT 완료까지 스레드 점유 → 동시 처리량 제한

### 2. 응답 시간 (Latency)
- **Async가 모든 구간에서 우수**
  - p50: 12.09ms vs 66.75ms (5.5배)
  - p95: 46.98ms vs 96.19ms (2배)
  - max: 86.25ms vs 359.27ms (4.2배)
- Sync latency가 높은 이유: HikariCP 풀(기본 10개) 경합 → 커넥션 대기 시간
- Async는 DB 커넥션을 사용하지 않으므로 경합 없음

### 3. 안정성
- Async max latency 86ms — **매우 안정적** (편차 작음)
- Sync max latency 359ms — 피크 시 4배 이상 스파이크 (DB 커넥션 풀 경합)

### 4. 정합성 (Consistency)
- 두 방식 모두 정확히 100,000건 발급, 초과 발급 0건
- Redis DECR 원자적 재고 차감이 핵심 — 방식과 무관하게 동작

## 병목 분석

| 병목 | Async | Sync |
|------|-------|------|
| DB 커넥션 풀 (HikariCP) | 영향 없음 (Consumer 별도) | **주요 병목** — 500 VU에서 10개 커넥션 경합 |
| Kafka produce | acks=all → 수 ms | 해당 없음 |
| Redis | 밀리초 이하 | 밀리초 이하 |
| 스레드 점유 | produce 후 즉시 해제 | DB INSERT 완료까지 점유 |

## 트러블슈팅

### 초기 Sync 테스트 실패 (port 고갈)
- **증상**: 첫 sync 500 VU 테스트에서 `can't assign requested address` 에러
- **원인**: 이전 빌드(issueCouponSync 미포함)에서 실행 → 모든 요청 500 에러 → `Connection: close` 반환 → TCP 커넥션 재활용 불가 → TIME_WAIT 누적 → ephemeral port 소진
- **해결**: 앱 재시작(새 빌드) 후 500 VU 정상 동작 확인
- **교훈**: 부하 테스트 전 반드시 엔드포인트 수동 검증 필요

## 결론

1. **동일 조건에서 Kafka 비동기가 전 지표에서 우수**
   - RPS 1.56배, p50 5.5배, p95 2배, max latency 4.2배 빠름
   - DB 커넥션 풀 경합 없음 → 안정적인 latency 분포

2. **Sync의 한계는 HikariCP 풀 경합**
   - 기본 10개 커넥션으로 500 VU 처리 시 심한 경합
   - 풀 사이즈 증가로 완화 가능하나 DB 부하도 함께 증가

3. **Async의 아키텍처적 이점**
   - API 계층과 DB 계층 완전 분리
   - Consumer 독립 스케일링 (파티션 수 조절)
   - DB 장애 시에도 API 응답 가능 (202 Accepted)
   - 피크 트래픽 → Kafka가 버퍼 → Consumer가 자체 속도로 처리

4. **PRD 100k TPS 달성을 위해서는 Kafka 비동기 + 파티션 확장(실험 C) 필요**
