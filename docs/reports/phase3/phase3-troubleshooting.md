# Phase 3 트러블슈팅 기록

## 점검 일자: 2026-02-28

## 인프라 상태

Docker Compose 인프라 정상 가동. Prometheus 타겟 4개 모두 UP 상태:
- `spring-boot` (app:8080)
- `kafka` (kafka JMX)
- `redis` (redis-exporter)
- `kafka-exporter`

---

## 문제 1: HTTP histogram bucket 미생성 (CRITICAL)

### 증상
- `curl actuator/prometheus | grep http_server_requests_seconds_bucket` → 결과 0개
- Grafana "HTTP p95 Latency" gauge, "HTTP Response Time p99" timeseries 패널 빈 값

### 원인
`application.yaml`에서 histogram 설정이 잘못된 프로퍼티 경로에 위치:

```yaml
# BEFORE (잘못된 경로 — management.prometheus.metrics.distribution)
management:
  prometheus:
    metrics:
      export:
        enabled: true
      distribution:                          # ← prometheus 하위에 중첩됨
        percentiles-histogram:
          http.server.requests: true
```

Spring Boot 3+ / Micrometer에서 distribution 설정은 `management.metrics.distribution`이 올바른 경로.
`management.prometheus.metrics.distribution`은 인식되지 않아 histogram bucket이 생성되지 않았음.

### 수정

```yaml
# AFTER (올바른 경로 — management.metrics.distribution)
management:
  prometheus:
    metrics:
      export:
        enabled: true
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5,0.95,0.99
```

### 검증
```bash
curl -s localhost:8080/actuator/prometheus | grep http_server_requests_seconds_bucket
# → bucket 라인 다수 출력 확인
```

---

## 문제 2: Kafka Request Latency 쿼리 부정확

### 증상
`kafka_network_requestmetrics_totaltimems{request="Produce"}` 쿼리가 11개 시리즈 반환.
`_objectname` label 내에 percentile 구분(50thPercentile, 75thPercentile 등)이 포함되어 있어 모든 percentile이 한 패널에 혼재.

### 수정
`quantile` label 필터를 추가하여 p50, p95만 명시적으로 표시:

```promql
# Produce p95
kafka_network_requestmetrics_totaltimems{job="kafka", request="Produce", quantile="0.95"}

# FetchConsumer p50
kafka_network_requestmetrics_totaltimems{job="kafka", request="FetchConsumer", quantile="0.50"}
```

---

## 문제 3: Datasource UID 비일관

### 증상
첫 번째 패널만 `"uid": "PBFA97CFB590B2093"` 명시, 나머지 17개 패널은 `{"type": "prometheus"}`만 사용.
Provisioning UID 일치로 현재 동작하지만, 이식성 문제 잠재.

### 수정
모든 패널의 datasource를 `{"type": "prometheus", "uid": "PBFA97CFB590B2093"}`로 통일.

---

## 문제 4: 실험 A Sync 테스트 시 ephemeral port 고갈

### 증상
- `k6 run --env MODE=sync` (500 VU) 실행 시 `can't assign requested address` 에러 대량 발생
- 모든 요청 실패, DB INSERT 0건

### 원인
앱을 재시작하지 않고 테스트 실행 → `issueCouponSync()` 메서드가 없는 이전 빌드에서 실행됨.
1. 모든 요청이 500 Internal Server Error 반환
2. Spring Boot가 에러 응답에 `Connection: close` 헤더 포함
3. k6가 HTTP keep-alive를 사용할 수 없어 매 요청마다 새 TCP 커넥션 생성
4. macOS ephemeral port 범위 16,384개 (49152~65535)가 TIME_WAIT로 소진

### 검증
```bash
# macOS ephemeral port 범위 확인
sysctl net.inet.ip.portrange.first net.inet.ip.portrange.last
# → 49152 ~ 65535 (16,384개)

# 앱 재시작 후 동일 500 VU 테스트 → 정상 동작
k6 run --env MODE=sync --stage '10s:10,5s:200,60s:200,5s:500,60s:500,10s:0' k6/phase3-experiment-a-spike.js
# → RPS 3,019/s, 100% 성공, DB 100,000건 정확 발급
```

### 교훈
- 부하 테스트 전 반드시 엔드포인트 수동 검증 (`curl`)
- 코드 변경 후 앱 재시작 확인
- `Connection: close`는 매 요청마다 새 TCP 커넥션 → 고부하에서 port 고갈 유발

---

## 수정 파일 요약

| 파일 | 변경 내용 |
|------|-----------|
| `src/main/resources/application.yaml` | `management.metrics.distribution`으로 경로 이동 |
| `infra/grafana/dashboards/spring-boot.json` | datasource UID 통일, Kafka 쿼리 percentile 필터 추가 |
