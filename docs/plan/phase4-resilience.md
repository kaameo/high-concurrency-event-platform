# Phase 4: 장애 복구 실험 (1~2주)

## 4.1 노드 장애 시뮬레이션

### 실험 환경
```
정상 상태:
  Control-plane: K8s Master + Ingress Controller
  Worker 1 (node-role=infra): Kafka, Redis, PostgreSQL
  Worker 2 (node-role=app): Spring Boot App (1~10 replicas)
```

### 시나리오 D-1: Infra 노드 장애

**절차:**
1. k6로 지속 부하 시작 (3,000 TPS)
2. `docker stop kind-worker` (Worker 1 강제 종료)
3. 관찰 시작

**관찰 항목:**
| 항목 | 명령어 | 기대 |
|------|--------|------|
| 노드 상태 변화 | `kubectl get nodes -w` | NotReady (40초~5분) |
| Pod 재배치 | `kubectl get pods -o wide -w` | Worker 2로 이동 |
| 서비스 중단 시간 | k6 에러율 그래프 | 에러 발생 구간 측정 |
| 데이터 유실 여부 | DB 발급 건수 vs Redis 차감 건수 | PV/PVC 있으면 유실 없음 |

**Pod Eviction 타임라인:**
```
0s    — Worker 1 종료
~40s  — kubelet heartbeat 실패, 노드 NotReady
~5m   — pod-eviction-timeout 도달, Pod Terminating
~5m+  — Scheduler가 Worker 2에 새 Pod 스케줄
~6m+  — Pod Running (이미지 pull 포함)
```

### 시나리오 D-2: App 노드 장애

**절차:**
1. k6로 지속 부하 시작
2. `docker stop kind-worker2` (Worker 2 강제 종료)
3. Spring Boot Pod이 Worker 1로 이동 시 인프라 간섭 관찰

**관찰 포인트:**
- 인프라(Kafka/Redis)와 App이 같은 노드에서 자원 경쟁
- CPU/Memory 사용량 급증 여부
- Kafka Consumer lag 변화

---

## 4.2 데이터 영속성 검증

### PV/PVC 설정

**StorageClass (kind용 local-path):**
```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: local-path
provisioner: rancher.io/local-path
reclaimPolicy: Retain
volumeBindingMode: WaitForFirstConsumer
```

**Kafka PVC:**
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: kafka-data
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: local-path
  resources:
    requests:
      storage: 5Gi
```

### 검증 절차
1. 장애 전: Kafka에 메시지 1,000건 발행, DB에 500건 저장 확인
2. Worker 1 종료
3. Worker 1 복구 (`docker start kind-worker`)
4. 장애 후: Kafka offset 확인, 미처리 메시지 Consumer 재처리 확인
5. 최종: DB 발급 건수 = 1,000건 확인 (유실 0건)

---

## 4.3 회복 탄력성 검증

### V자형 회복 곡선 획득

**절차:**
1. k6 부하 시작 (5,000 TPS, 5분간)
2. 2분 경과 시점에 Worker 1 종료
3. 그래프 관찰:
   - TPS 급락 (V의 바닥)
   - Pod 재배치 후 TPS 회복 (V의 상승)
4. 회복 완료 시점 기록

**Grafana 캡처 대상:**
- HTTP Request Rate (TPS) — V자형 곡선
- Error Rate — spike 후 복구
- Kafka Consumer Lag — 급증 후 감소
- Pod Count — 재배치 과정
- CPU/Memory — 자원 재분배

### Spring Boot Reconnection 확인
- Redis: Lettuce 자동 재연결 (기본 활성화)
- Kafka: Consumer rebalance 후 자동 재연결 (backoff 500/5000ms, session-timeout 15s, heartbeat 3s)
- Kafka Producer: 재연결 최적화 (backoff 500/5000ms, request-timeout 10s, delivery-timeout 30s)
- PostgreSQL: HikariCP 재연결 최적화 (connectionTimeout 3s, keepaliveTime 30s, maxLifetime 60s, initializationFailTimeout 0)
- Circuit Breaker: Resilience4j 프로그래매틱 적용 (장애 시 30s 타임아웃 → 즉시 503 반환)

### Pod Anti-Affinity 실험

**설정:**
```yaml
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchLabels:
            app: kafka
        topologyKey: "kubernetes.io/hostname"
```

**검증:** Kafka 브로커(복수 설치 시)가 서로 다른 노드에 배치되는지 확인

### MTTR 측정
| 지표 | 정의 | 목표 |
|------|------|------|
| Detection Time | 노드 종료 → NotReady | < 1분 |
| Recovery Time | NotReady → Pod Running | < 6분 |
| Service Restoration | Pod Running → TPS 정상 | < 8분 |
| Total MTTR | 종료 → TPS 정상 | < 10분 |

---

## Phase 4 완료 기준
- [x] k6 장애 복구 시나리오 작성 (`k6/phase4-resilience.js`)
- [x] 실험 실행 스크립트 작성 (`k8s/run-experiment-d.sh`)
- [x] Spring Boot reconnection 설정 추가 (`application.yaml`)
- [x] ADR-009 장애 복구 전략 작성
- [x] 시나리오 D-1, D-2 실행 및 MTTR 측정
- [x] 결과 리포트 작성 (`docs/reports/phase4/experiment-d-report.md`)
- [ ] PV/PVC 기반 데이터 유실 0건 검증
- [ ] V자형 회복 곡선 Grafana 캡처 획득

### Phase 4-A: 장애 복구 개선 (코드/설정 레벨)
- [x] Resilience4j Circuit Breaker 프로그래매틱 적용 (`CircuitBreakerConfig.java`)
- [x] ServiceUnavailableException + 503 핸들러 추가
- [x] HikariCP 재연결 최적화 (connectionTimeout 3s, keepaliveTime 30s, initializationFailTimeout 0)
- [x] Kafka Producer/Consumer 재연결 최적화 (backoff 500/5000ms, session-timeout 15s)
- [x] App 다중 레플리카 (replicas 2) + topologySpreadConstraints 노드 분산 배치
- [x] ADR-009 적용 완료 항목 반영
- [x] Testcontainers 공유 @TestConfiguration 리팩토링 (Spring Boot 4 @ServiceConnection)
- [x] 트러블슈팅 리포트 작성 (`docs/reports/phase4/phase4a-troubleshooting.md`)
- [ ] kind 클러스터 재배포 후 실험 D 재실행 — MTTR 3~5분 달성 검증
