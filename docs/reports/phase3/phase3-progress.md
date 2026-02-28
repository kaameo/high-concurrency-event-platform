# Phase 3: 성능 실험 및 검증 — 진행 보고서

- **작성일**: 2026-02-28
- **최종 수정일**: 2026-02-28
- **상태**: 진행 중

---

## 의사결정

### Locust 제외
k6가 custom metrics, JSON export, handleSummary를 이미 갖추고 있어 Locust는 불필요한 중복.
기존 `k6/baseline-load-test.js` (Phase 1), `k6/phase2-load-test.js` (Phase 2) 패턴을 재활용.

### 실험 B (HPA) 스킵
K8s 클러스터 미구축 상태. 로컬 Docker Compose 환경에서 실행 가능한 실험 A, C에 집중.
HPA 실험은 Phase 4 (K8s 배포) 이후로 이관.

---

## 전체 체크리스트

| # | 항목 | 상태 |
|---|------|------|
| 0 | Grafana 대시보드 수정 (histogram, UID, Kafka 쿼리) | **완료** |
| 1 | k6 실험 A 시나리오 작성 — Spike 테스트 (`k6/phase3-experiment-a-spike.js`) | **완료** |
| 2 | 실험 A: DB 직결 API (`POST /api/v1/coupons/issue-sync`) 구현 | **완료** |
| 3 | 실험 A 실행 및 결과 리포트 | **완료** |
| 4 | 실험 C: Kafka 파티션 튜닝 시나리오 + 실행 | 미구현 |
| 5 | KPI 검증 (100k TPS, 99.9%, p95≤200ms) | 미구현 |
| 6 | 실험 결과 리포트 작성 | 미구현 |

---

## Step 0: Grafana 대시보드 수정 (완료)

상세 내역은 [phase3-troubleshooting.md](./phase3-troubleshooting.md) 참조.

| # | 문제 | 심각도 | 수정 내용 |
|---|------|--------|-----------|
| 1 | HTTP histogram bucket 미생성 | CRITICAL | `application.yaml` — `management.metrics.distribution`으로 경로 이동 |
| 2 | Kafka Request Latency 쿼리 부정확 | MEDIUM | `spring-boot.json` — `quantile` label 필터 추가 (p50/p95) |
| 3 | Datasource UID 비일관 | LOW | `spring-boot.json` — 19개 전체 패널 UID 통일 |

### 검증 결과

| 항목 | 결과 |
|------|------|
| `http_server_requests_seconds_bucket` | 69개 bucket (수정 전 0개) |
| Grafana health | HTTP 200 OK |
| 패널 datasource UID | 19/19 통일 |

---

## Step 1-3: 실험 A — DB 직결 vs Kafka 버퍼링

### 구현 범위
1. `CouponIssueService.issueCouponSync()` — DB 직결 동기 발급 메서드
2. `CouponController` — `POST /api/v1/coupons/issue-sync` 엔드포인트
3. `k6/phase3-experiment-a-spike.js` — Spike 시나리오 (0→10,000 VU, 5초 ramp)
4. 동일 부하로 `/issue` (async) vs `/issue-sync` (sync) 비교 실행
5. 결과 리포트: `docs/reports/phase3/experiment-a-report.md`

### 측정 항목
| 항목 | 측정 방법 |
|------|-----------|
| 응답 시간 (p50/p95/p99) | k6 `http_req_duration` |
| 처리량 (RPS) | k6 `http_reqs` rate |
| DB 커넥션 풀 사용량 | HikariCP 메트릭 (Grafana) |
| 에러율 | k6 `http_req_failed` |

---

## Step 4: 실험 C — Kafka 파티션 튜닝

### 구현 범위
1. Docker Compose 파티션 수 변경 (1 → 3 → 10)
2. k6 고부하 시나리오로 Consumer Lag + 처리량 비교
3. 결과 리포트: `docs/reports/phase3/experiment-c-report.md`

---

## Step 5: KPI 검증

실험 A/C 최적 구성으로 고정 후 PRD KPI 달성 여부 확인:

| KPI | 목표 |
|-----|------|
| 피크 처리량 | 100k TPS |
| 성공률 | ≥ 99.9% |
| Latency p95 | ≤ 200ms |
| Latency p99 | ≤ 500ms |

---

## 인프라 현황

| 서비스 | 상태 | 용도 |
|--------|------|------|
| postgres | UP | Primary DB |
| redis | UP | 재고 관리, Rate Limit |
| kafka | UP | 비동기 메시지 파이프라인 |
| kafka-jmx-exporter | UP | Kafka JMX 메트릭 수집 |
| kafka-exporter | UP | Consumer Lag 메트릭 |
| redis-exporter | UP | Redis 메트릭 수집 |
| prometheus | UP | 메트릭 스크래핑 (타겟 4개 모두 UP) |
| grafana | UP | 대시보드 (19개 패널) |
| kafka-ui | UP | Kafka 토픽/메시지 관리 UI |

---

## 수정/신규 파일

### 수정 파일
| 파일 | 변경 내용 |
|------|-----------|
| `src/main/resources/application.yaml` | histogram 프로퍼티 경로 수정 |
| `infra/grafana/dashboards/spring-boot.json` | datasource UID 통일, Kafka 쿼리 수정 |

### 신규 파일 (완료)
| 파일 | 용도 |
|------|------|
| `docs/reports/phase3/phase3-troubleshooting.md` | 대시보드 트러블슈팅 기록 |
| `docs/reports/phase3/phase3-progress.md` | Phase 3 진행 보고서 (본 문서) |

### 신규 파일 (예정)
| 파일 | 용도 |
|------|------|
| `CouponIssueService.issueCouponSync()` | DB 직결 동기 발급 |
| `CouponController` — `/issue-sync` | 실험 A 엔드포인트 |
| `k6/phase3-experiment-a-spike.js` | Spike 부하 테스트 |
| `docs/reports/phase3/experiment-a-report.md` | 실험 A 결과 |
| `docs/reports/phase3/experiment-c-report.md` | 실험 C 결과 |
