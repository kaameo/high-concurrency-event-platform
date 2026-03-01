# Kind 클러스터 접속 URL

## 애플리케이션

| 서비스 | URL | 비고 |
|--------|-----|------|
| App (API) | http://localhost:30080 | NodePort 30080 |
| Health Check | http://localhost:30080/actuator/health | |
| Prometheus Metrics | http://localhost:30080/actuator/prometheus | |
| Swagger UI | http://localhost:30080/swagger-ui.html | |

## 모니터링

| 서비스 | URL | 인증 |
|--------|-----|------|
| Grafana | http://localhost:30300 | admin / prom-operator |
| Prometheus | 클러스터 내부 전용 | `kubectl port-forward svc/prometheus-kube-prometheus-prometheus 9090:9090` |
| Alertmanager | 클러스터 내부 전용 | `kubectl port-forward svc/prometheus-kube-prometheus-alertmanager 9093:9093` |

## 인프라 (클러스터 내부)

| 서비스 | K8s Service | Port |
|--------|-------------|------|
| PostgreSQL | postgresql:5432 | 5432 |
| Redis | redis-master:6379 | 6379 |
| Kafka | kafka-0.kafka.default.svc.cluster.local:9092 | 9092 |

### 로컬에서 인프라 직접 접근 (port-forward)

```bash
# PostgreSQL
kubectl port-forward svc/postgresql 5432:5432

# Redis
kubectl port-forward svc/redis-master 6379:6379

# Kafka
kubectl port-forward svc/kafka 9092:9092
```

## 유용한 kubectl 명령어

```bash
# 전체 Pod 상태
kubectl get pods

# HPA 모니터링
kubectl get hpa -w

# 앱 로그
kubectl logs -f deployment/event-platform

# 노드 리소스 사용량
kubectl top nodes

# Pod 리소스 사용량
kubectl top pods
```
