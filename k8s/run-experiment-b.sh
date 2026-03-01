#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CLUSTER_NAME="event-platform"
NAMESPACE="default"

log() { echo -e "\n=== $1 ===\n"; }

# ----- Step 1: Create kind cluster -----
log "Step 1: Creating kind cluster"
if kind get clusters 2>/dev/null | grep -q "$CLUSTER_NAME"; then
  echo "Cluster '$CLUSTER_NAME' already exists, skipping creation."
else
  kind create cluster --config "$SCRIPT_DIR/kind-config.yaml"
fi
kubectl cluster-info --context "kind-$CLUSTER_NAME"

# ----- Step 2: Install metrics-server -----
log "Step 2: Installing metrics-server"
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml || true
kubectl patch deployment metrics-server -n kube-system \
  --type='json' \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' 2>/dev/null || true

# ----- Step 3: Add Helm repos -----
log "Step 3: Adding Helm repos"
helm repo add bitnami https://charts.bitnami.com/bitnami 2>/dev/null || true
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
helm repo update

# ----- Step 4: Deploy infrastructure -----
log "Step 4: Deploying PostgreSQL"
helm upgrade --install postgresql bitnami/postgresql \
  -f "$SCRIPT_DIR/values/postgresql-values.yaml" --wait --timeout 5m

log "Step 4: Deploying Redis"
helm upgrade --install redis bitnami/redis \
  -f "$SCRIPT_DIR/values/redis-values.yaml" --wait --timeout 5m

log "Step 4: Deploying Kafka (apache/kafka manifest)"
docker pull apache/kafka:3.9.0 2>/dev/null || true
docker save apache/kafka:3.9.0 | docker exec -i "${CLUSTER_NAME}-control-plane" ctr --namespace=k8s.io images import - 2>/dev/null || true
docker save apache/kafka:3.9.0 | docker exec -i "${CLUSTER_NAME}-worker" ctr --namespace=k8s.io images import - 2>/dev/null || true
docker save apache/kafka:3.9.0 | docker exec -i "${CLUSTER_NAME}-worker2" ctr --namespace=k8s.io images import - 2>/dev/null || true
kubectl apply -f "$SCRIPT_DIR/kafka/kafka.yaml"
echo "Waiting for Kafka to be ready..."
kubectl rollout status statefulset/kafka --timeout=120s

# ----- Step 5: Build & load app image -----
log "Step 5: Building app image"
cd "$PROJECT_DIR"
docker build -t event-platform:latest .
kind load docker-image event-platform:latest --name "$CLUSTER_NAME"

# ----- Step 6: Deploy app -----
log "Step 6: Deploying application"
kubectl apply -f "$SCRIPT_DIR/app/configmap.yaml"
kubectl apply -f "$SCRIPT_DIR/app/deployment.yaml"
kubectl apply -f "$SCRIPT_DIR/app/service.yaml"

log "Step 6: Waiting for app to be ready"
kubectl rollout status deployment/event-platform --timeout=120s

# ----- Step 7: Deploy monitoring -----
log "Step 7: Deploying Prometheus + Grafana"
helm upgrade --install prometheus prometheus-community/kube-prometheus-stack \
  --set grafana.service.type=NodePort \
  --set grafana.service.nodePort=30000 \
  --wait --timeout 5m

# ----- Step 8: Verify -----
log "Step 8: Verifying deployment"
kubectl get pods
echo ""
echo "Waiting 10s for endpoints to settle..."
sleep 10

APP_URL="http://localhost:30080"
echo "Health check: $APP_URL/actuator/health"
curl -sf "$APP_URL/actuator/health" && echo "" || echo "Health check not yet available (app may still be starting)"

# ----- Step 9: Run experiments -----
run_experiment() {
  local config=$1
  local hpa_file=$2
  local label=$3

  log "Experiment $label: Applying HPA ($config)"

  # Reset replicas to 1
  kubectl scale deployment/event-platform --replicas=1
  kubectl rollout status deployment/event-platform --timeout=60s

  # Remove existing HPAs
  kubectl delete hpa --all 2>/dev/null || true

  # Apply HPA
  kubectl apply -f "$SCRIPT_DIR/app/$hpa_file"

  # Call init API to reset coupon state
  curl -sf -X POST "$APP_URL/api/v1/admin/coupons/019577a0-0000-7000-8000-000000000001/init-stock" || true
  sleep 5

  log "Experiment $label: Running k6 spike test"
  k6 run --env BASE_URL="$APP_URL" --env HPA_CONFIG="$config" "$PROJECT_DIR/k6/phase3-experiment-b-hpa.js" || true

  # Record final pod count
  log "Experiment $label: Final pod count"
  kubectl get pods -l app=event-platform
  kubectl get hpa

  sleep 30  # cooldown between experiments
}

log "Starting HPA experiments"

run_experiment "cpu" "hpa-cpu.yaml" "B-1 (CPU)"
run_experiment "memory" "hpa-memory.yaml" "B-2 (Memory)"
run_experiment "custom" "hpa-custom.yaml" "B-3 (Custom RPS)"

# ----- Done -----
log "All experiments complete!"
echo "Results saved to k6/phase3-experiment-b-*-result.json"
echo "View HPA events: kubectl describe hpa"
echo "View pod scaling: kubectl get events --sort-by=.metadata.creationTimestamp"
