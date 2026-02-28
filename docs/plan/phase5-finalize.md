# Phase 5: 마무리 및 문서화 (1주)

## 5.1 CI/CD (GitHub Actions) — 보류

### 파이프라인 구성 (`.github/workflows/`)

#### `ci.yml` — PR/Push 시 자동 실행
```yaml
trigger: push (main), pull_request (main)

jobs:
  test:
    - Java 21 Setup
    - Gradle Build
    - Unit/Integration Test (Testcontainers)
    - Test Coverage Report

  build:
    needs: test
    - Docker Image Build
    - Docker Image Push (GitHub Container Registry)
```

#### `deploy.yml` — main 머지 시 실행
```yaml
trigger: push (main) - merge only

jobs:
  deploy:
    - kind 클러스터에 배포 (kubectl apply)
    - Health Check
    - Smoke Test (k6 경량 시나리오)
```

### Dockerfile
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/event-platform-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Multi-stage Build (최적화)
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/event-platform-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 5.2 문서화

### README.md 구성
```
# High-Concurrency Event Platform

> 한 줄 설명 + 배지 (CI, Coverage, License)

## Architecture
- 시스템 아키텍처 다이어그램 (Mermaid)
- 데이터 흐름도

## Tech Stack
- 기술 스택 테이블

## Quick Start

### Option A: Docker Compose (로컬 개발)
- Prerequisites: Java 21, Docker
- cp .env.example .env
- docker-compose up
- Swagger UI: localhost:8080/swagger-ui.html
- Kafka UI: localhost:9091
- Grafana: localhost:3000

### Option B: kind (K8s 실험 환경)
- Prerequisites: Java 21, Docker, kind, kubectl
- kind create cluster --config infra/kind/config.yaml
- kubectl apply -f infra/k8s/
- 부하 테스트, HPA, 장애 복구 실험은 이 환경에서 수행

## Benchmark Results
- 실험 A/B/C 요약 + Grafana 캡처 이미지
- 핵심 수치 (TPS, Latency, 개선율)

## Troubleshooting
- 주요 장애 사례 및 해결 과정

## Project Structure
- 패키지 구조 트리

## ADR (Architecture Decision Records)
- ADR 목록 링크
```

### ADR 작성 (`docs/adr/`)
| ADR | 주제 |
|-----|------|
| ADR-001 | Redis DECR vs DB Pessimistic Lock 선택 이유 |
| ADR-002 | Kafka 도입 및 Write-Behind 패턴 선택 이유 |
| ADR-003 | Java 21 Virtual Threads 도입 배경 |
| ADR-004 | HPA 메트릭 선택 근거 (실험 B 결과 기반) |
| ADR-005 | Kafka 파티션 수 결정 근거 (실험 C 결과 기반) |

**ADR 템플릿:**
```markdown
# ADR-XXX: 제목

## Status
Accepted

## Context
어떤 문제 상황이었는가

## Decision
무엇을 선택했는가

## Consequences
장점, 단점, 트레이드오프
```

### 종합 성능 리포트 (`docs/reports/`)
| 리포트 | 내용 |
|--------|------|
| `experiment-a-result.md` | DB 직결 vs Kafka 버퍼링 |
| `experiment-b-result.md` | HPA Auto-scaling 최적화 |
| `experiment-c-result.md` | Kafka 파티션 튜닝 |
| `resilience-test-result.md` | 노드 장애 복구 실험 |
| `summary.md` | 종합 분석 및 결론 |

### 프로젝트 최종 디렉토리 구조
```
high-concurrency-event-platform/
├── src/
│   ├── main/java/com/kaameo/event_platform/
│   └── test/java/com/kaameo/event_platform/
├── infra/
│   ├── kind/
│   │   └── config.yaml
│   ├── k8s/
│   │   ├── app/
│   │   ├── kafka/
│   │   ├── redis/
│   │   ├── postgres/
│   │   └── monitoring/
│   └── prometheus/
│       └── prometheus.yml
├── loadtest/
│   ├── k6/
│   │   ├── coupon-issue.js
│   │   └── spike-test.js
│   └── locust/
│       └── locustfile.py
├── docs/
│   ├── PRD.md
│   ├── PLAN.md
│   ├── plan/
│   │   └── (phase 문서들)
│   ├── prd/
│   │   └── (원본 요구사항)
│   ├── adr/
│   │   ├── ADR-001-redis-decr.md
│   │   └── ...
│   └── reports/
│       ├── assets/grafana/
│       ├── raw/
│       ├── experiment-a-result.md
│       └── ...
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── .gitignore
└── README.md
```

---

## Phase 5 완료 기준
- [ ] ~~GitHub Actions CI 파이프라인 그린~~ (보류)
- [ ] Docker 이미지 빌드 및 실행 확인
- [ ] README.md에 아키텍처 다이어그램 + 벤치마크 결과 포함
- [ ] ADR 최소 5건 작성
- [ ] 실험 결과 리포트 4건 작성
- [ ] `cp .env.example .env && docker-compose up`으로 누구나 재현 가능
- [ ] Kafka UI (`localhost:9091`)에서 토픽/메시지 확인 가능

### 5.3 산출물 링크 표준화
- [ ] Phase별 산출물 링크 템플릿 정의 (`docs/reports/phase-{n}.md`)
- [ ] 대시보드 스냅샷 경로 규칙 정의 (`docs/reports/assets/grafana/`)
- [ ] ADR 파일명 규칙 정의 (`docs/adr/ADR-xxxx-title.md`)
- [ ] 실험 원본 데이터 경로 규칙 정의 (`docs/reports/raw/`)
