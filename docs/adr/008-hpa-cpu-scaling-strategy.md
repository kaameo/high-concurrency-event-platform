# ADR-008: CPU 기반 HPA Auto-scaling 전략 채택

- **Status**: Accepted
- **Date**: 2026-03-01
- **Deciders**: 개발팀
- **Experiment**: Phase 3 실험 B

## Context

K8s 환경에서 트래픽 급증 시 자동 스케일링이 필요하다. HPA(Horizontal Pod Autoscaler)는 다양한 메트릭을 스케일링 트리거로 사용할 수 있으며, 어떤 메트릭이 Spring Boot + JVM 앱에 최적인지 실증 비교가 필요했다.

## Decision Drivers

- 스파이크 트래픽(0 → 10,000 VU) 대응 능력
- Pod 안정성 (OOM Kill, CrashLoop 방지)
- 스케일업 반응 속도
- JVM 앱 특성과의 적합성

## Considered Options

### Option 1: CPU Utilization (averageUtilization: 50%)
Pod 평균 CPU 사용률 기반. 요청 처리량에 직접 비례.

### Option 2: Memory Utilization (averageUtilization: 70%)
Pod 평균 메모리 사용률 기반. JVM 힙 사용량 반영.

### Option 3: CPU + Memory 복합 (CPU 40% + Memory 60%)
두 메트릭 중 하나라도 초과 시 스케일업.

### Option 4: Custom Metric (HTTP RPS)
Prometheus Adapter를 통한 비즈니스 메트릭 기반 스케일링. (미실험 — Prometheus Adapter 미설치)

## Experiment Results (10,000 VU Spike, kind 클러스터)

| 구성 | 최대 Pod | RPS | p95 Latency | Success Rate | 안정성 |
|------|----------|-----|-------------|-------------|--------|
| **CPU (B-1)** | 8 | 1,329 | 24.42s | **76.76%** | Pod 재시작 없음 |
| Memory (B-2) | 10 | 1,169 | 59.99s | 72.36% | OOM Kill 다수 |
| 복합 (B-3) | 10 | 695 | 54.47s | 25.99% | CrashLoopBackOff |

## Decision

**CPU Utilization 기반 HPA를 채택한다** (Option 1).

## Rationale

### CPU가 최적인 이유

1. **부하에 비례**: HTTP 요청 처리는 CPU를 직접 소비하므로, CPU 사용률이 트래픽 증감을 가장 정확하게 반영
2. **빠른 반응**: CPU 변동은 즉시 감지됨 (15~30초 내 스케일업 시작)
3. **안정적 해소**: 부하가 줄면 CPU도 즉시 내려가 불필요한 과잉 스케일업 방지

### Memory가 부적합한 이유

1. **JVM GC 특성**: GC가 실행되기 전까지 힙 메모리가 해제되지 않음
2. **부하 감소 ≠ 메모리 감소**: 요청이 줄어도 메모리 사용률은 높게 유지
3. **OOM Kill 위험**: 메모리 기반 스케일업 시 새 Pod도 메모리를 소비 → 노드 리소스 고갈 → OOM Kill 연쇄

### 복합 메트릭이 부적합한 이유

1. **과도한 민감성**: 낮은 임계값 설정 시 불필요한 스케일업 발생
2. **리소스 경합**: 과잉 Pod가 노드 리소스를 경합 → CrashLoopBackOff
3. **튜닝 난이도**: 두 메트릭의 임계값을 동시에 최적화하기 어려움

## Production 권장 설정

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 2        # 고가용성
  maxReplicas: 10       # 클러스터 용량에 따라 조정
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 50
```

## Consequences

### 확정 사항
- CPU 기반 HPA를 production 스케일링 전략으로 확정
- `averageUtilization: 50%`, `minReplicas: 2` 기본 설정
- Memory 기반 HPA는 JVM 앱에서 사용하지 않음

### 향후 과제
- Prometheus Adapter 설치 후 Custom Metric(HTTP RPS, Kafka Consumer Lag) 기반 HPA 실험
- 프로덕션 클러스터(충분한 노드 리소스)에서 재실험하여 로컬 환경 한계 해소
- VPA(Vertical Pod Autoscaler)와의 조합 검토

## References

- [실험 B 상세 리포트](../reports/phase3/experiment-b-report.md)
- [K8s HPA Documentation](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)
- [Prometheus Adapter](https://github.com/kubernetes-sigs/prometheus-adapter)
