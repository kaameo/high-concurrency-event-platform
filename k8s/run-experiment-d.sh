#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CLUSTER_NAME="event-platform"
NAMESPACE="default"
APP_URL="http://localhost:30080"
COUPON_EVENT_ID="019577a0-0000-7000-8000-000000000001"
RESULTS_DIR="$PROJECT_DIR/docs/reports/phase4"
LOG_DIR="$PROJECT_DIR/k6"

log() { echo -e "\n=== $(date '+%H:%M:%S') $1 ===\n"; }
timestamp() { date '+%Y-%m-%dT%H:%M:%S'; }

mkdir -p "$RESULTS_DIR" "$LOG_DIR"

# ----- Step 0: Pre-flight checks -----
preflight() {
  log "Pre-flight: Checking cluster & cleaning up"

  kubectl cluster-info --context "kind-$CLUSTER_NAME" >/dev/null 2>&1 || {
    echo "ERROR: kind cluster '$CLUSTER_NAME' not found. Run run-experiment-b.sh first."
    exit 1
  }

  # Delete existing HPAs
  kubectl delete hpa --all 2>/dev/null || true

  # Reset app replicas to 1
  kubectl scale deployment/event-platform --replicas=1 2>/dev/null || true
  kubectl rollout status deployment/event-platform --timeout=120s

  # Restart app to clear any CrashLoopBackOff
  kubectl rollout restart deployment/event-platform
  kubectl rollout status deployment/event-platform --timeout=120s

  # Verify nodes
  echo "Cluster nodes:"
  kubectl get nodes -o wide

  # Verify pods
  echo ""
  echo "Running pods:"
  kubectl get pods -o wide

  # Health check
  echo ""
  echo "App health check..."
  for i in $(seq 1 10); do
    if curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1; then
      echo "App is healthy"
      break
    fi
    echo "Waiting for app... ($i/10)"
    sleep 5
  done

  # Show pod placement
  echo ""
  echo "Pod placement by node:"
  kubectl get pods -o wide --no-headers | awk '{printf "  %-45s %s\n", $1, $7}'
}

# ----- Init stock -----
init_stock() {
  local stock=${1:-100000}
  log "Initializing coupon stock: $stock"
  curl -sf -X POST "$APP_URL/api/v1/admin/coupons/$COUPON_EVENT_ID/init-stock?stock=$stock" || {
    curl -sf -X POST "$APP_URL/api/v1/admin/coupons/$COUPON_EVENT_ID/init-stock" || true
  }
  sleep 3
}

# ----- Background monitoring -----
start_monitoring() {
  local label=$1
  local log_prefix="$LOG_DIR/phase4-${label}"

  kubectl get nodes -w > "${log_prefix}-nodes.log" 2>&1 &
  echo $! > /tmp/phase4-nodes-pid

  kubectl get pods -o wide -w > "${log_prefix}-pods.log" 2>&1 &
  echo $! > /tmp/phase4-pods-pid

  echo "Monitoring started (logs: ${log_prefix}-nodes.log, ${log_prefix}-pods.log)"
}

stop_monitoring() {
  kill "$(cat /tmp/phase4-nodes-pid 2>/dev/null)" 2>/dev/null || true
  kill "$(cat /tmp/phase4-pods-pid 2>/dev/null)" 2>/dev/null || true
  rm -f /tmp/phase4-nodes-pid /tmp/phase4-pods-pid
}

# ----- Wait for node ready -----
wait_node_ready() {
  local node=$1
  local timeout=${2:-600}
  echo "Waiting for node $node to become Ready (timeout: ${timeout}s)..."
  local start=$(date +%s)
  while true; do
    local status=$(kubectl get node "$node" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
    if [[ "$status" == "True" ]]; then
      local elapsed=$(( $(date +%s) - start ))
      echo "Node $node is Ready (took ${elapsed}s)"
      return 0
    fi
    if (( $(date +%s) - start > timeout )); then
      echo "TIMEOUT: Node $node not ready after ${timeout}s"
      return 1
    fi
    sleep 5
  done
}

# ----- Wait for pods ready -----
wait_pods_ready() {
  local timeout=${1:-600}
  echo "Waiting for all pods to be Running (timeout: ${timeout}s)..."
  local start=$(date +%s)
  while true; do
    local not_ready=$(kubectl get pods --no-headers 2>/dev/null | grep -v "Running\|Completed" | wc -l | tr -d ' ')
    if [[ "$not_ready" == "0" ]]; then
      local elapsed=$(( $(date +%s) - start ))
      echo "All pods Running (took ${elapsed}s)"
      return 0
    fi
    if (( $(date +%s) - start > timeout )); then
      echo "TIMEOUT: Some pods not ready after ${timeout}s"
      kubectl get pods --no-headers | grep -v "Running\|Completed"
      return 1
    fi
    sleep 5
  done
}

# ===== Experiment D-1: Infra Node Failure =====
run_d1() {
  log "Experiment D-1: Infra Node Failure (Worker 2 — Kafka/PostgreSQL)"

  init_stock 100000

  local MTTR_LOG="$RESULTS_DIR/d1-mttr.txt"
  echo "=== D-1 MTTR Log ===" > "$MTTR_LOG"

  start_monitoring "d1"

  # Start k6 in background
  log "D-1: Starting k6 sustained load (3,000 VU, 10 min)"
  k6 run --env BASE_URL="$APP_URL" --env SCENARIO=d1 \
    "$PROJECT_DIR/k6/phase4-resilience.js" &
  K6_PID=$!
  echo "k6 PID: $K6_PID"

  # Wait 2 minutes for baseline
  echo "Waiting 120s for baseline metrics..."
  sleep 120

  # Inject failure: stop Worker 2 (infra node — Kafka/PostgreSQL)
  local T_STOP=$(timestamp)
  log "D-1: INJECTING FAILURE — docker stop ${CLUSTER_NAME}-worker2"
  echo "T_STOP=$T_STOP" >> "$MTTR_LOG"
  docker stop "${CLUSTER_NAME}-worker2"

  # Monitor NotReady
  echo "Waiting for node to become NotReady..."
  sleep 10
  for i in $(seq 1 30); do
    local status=$(kubectl get node "${CLUSTER_NAME}-worker2" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
    if [[ "$status" != "True" ]]; then
      local T_NOTREADY=$(timestamp)
      echo "T_NOTREADY=$T_NOTREADY" >> "$MTTR_LOG"
      echo "Node NotReady at $T_NOTREADY"
      break
    fi
    sleep 5
  done

  # Wait 3 minutes then recover
  echo "Waiting 180s before recovery..."
  sleep 180

  # Recover
  local T_RECOVER=$(timestamp)
  log "D-1: RECOVERING — docker start ${CLUSTER_NAME}-worker2"
  echo "T_RECOVER=$T_RECOVER" >> "$MTTR_LOG"
  docker start "${CLUSTER_NAME}-worker2"

  # Wait for node ready
  wait_node_ready "${CLUSTER_NAME}-worker2" 300
  local T_NODE_READY=$(timestamp)
  echo "T_NODE_READY=$T_NODE_READY" >> "$MTTR_LOG"

  # Wait for pods ready
  wait_pods_ready 300
  local T_PODS_READY=$(timestamp)
  echo "T_PODS_READY=$T_PODS_READY" >> "$MTTR_LOG"

  # Wait for k6 to finish
  echo "Waiting for k6 to complete..."
  wait $K6_PID || true

  local T_END=$(timestamp)
  echo "T_END=$T_END" >> "$MTTR_LOG"

  stop_monitoring

  log "D-1: Complete. MTTR log: $MTTR_LOG"
  cat "$MTTR_LOG"
  echo ""
  kubectl get pods -o wide
}

# ===== Experiment D-2: App Node Failure =====
run_d2() {
  log "Experiment D-2: App Node Failure (Worker 1 — App/Redis)"

  init_stock 100000

  local MTTR_LOG="$RESULTS_DIR/d2-mttr.txt"
  echo "=== D-2 MTTR Log ===" > "$MTTR_LOG"

  start_monitoring "d2"

  # Start k6 in background
  log "D-2: Starting k6 sustained load (3,000 VU, 10 min)"
  k6 run --env BASE_URL="$APP_URL" --env SCENARIO=d2 \
    "$PROJECT_DIR/k6/phase4-resilience.js" &
  K6_PID=$!
  echo "k6 PID: $K6_PID"

  # Wait 2 minutes for baseline
  echo "Waiting 120s for baseline metrics..."
  sleep 120

  # Inject failure: stop Worker 1 (app/Redis node)
  local T_STOP=$(timestamp)
  log "D-2: INJECTING FAILURE — docker stop ${CLUSTER_NAME}-worker"
  echo "T_STOP=$T_STOP" >> "$MTTR_LOG"
  docker stop "${CLUSTER_NAME}-worker"

  # Monitor NotReady
  echo "Waiting for node to become NotReady..."
  sleep 10
  for i in $(seq 1 30); do
    local status=$(kubectl get node "${CLUSTER_NAME}-worker" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
    if [[ "$status" != "True" ]]; then
      local T_NOTREADY=$(timestamp)
      echo "T_NOTREADY=$T_NOTREADY" >> "$MTTR_LOG"
      echo "Node NotReady at $T_NOTREADY"
      break
    fi
    sleep 5
  done

  # Wait 3 minutes then recover
  echo "Waiting 180s before recovery..."
  sleep 180

  # Recover
  local T_RECOVER=$(timestamp)
  log "D-2: RECOVERING — docker start ${CLUSTER_NAME}-worker"
  echo "T_RECOVER=$T_RECOVER" >> "$MTTR_LOG"
  docker start "${CLUSTER_NAME}-worker"

  # Wait for node ready
  wait_node_ready "${CLUSTER_NAME}-worker" 300
  local T_NODE_READY=$(timestamp)
  echo "T_NODE_READY=$T_NODE_READY" >> "$MTTR_LOG"

  # Wait for pods ready
  wait_pods_ready 300
  local T_PODS_READY=$(timestamp)
  echo "T_PODS_READY=$T_PODS_READY" >> "$MTTR_LOG"

  # Wait for k6 to finish
  echo "Waiting for k6 to complete..."
  wait $K6_PID || true

  local T_END=$(timestamp)
  echo "T_END=$T_END" >> "$MTTR_LOG"

  stop_monitoring

  log "D-2: Complete. MTTR log: $MTTR_LOG"
  cat "$MTTR_LOG"
  echo ""
  kubectl get pods -o wide
}

# ===== Data Persistence Test =====
run_persistence() {
  log "Data Persistence Test"

  init_stock 1000

  local RESULT_LOG="$RESULTS_DIR/persistence-result.txt"
  echo "=== Data Persistence Test ===" > "$RESULT_LOG"

  # Step 1: Issue 1,000 coupons with small k6 run
  log "Persistence: Issuing 1,000 coupons"
  k6 run --env BASE_URL="$APP_URL" --env SCENARIO=persistence \
    --vus 50 --duration 30s \
    "$PROJECT_DIR/k6/phase4-resilience.js" || true

  sleep 10

  # Step 2: Record pre-failure DB count
  local APP_POD=$(kubectl get pods -l app=event-platform -o jsonpath='{.items[0].metadata.name}')
  echo "Pre-failure state recorded" >> "$RESULT_LOG"
  echo "T_PRE_FAILURE=$(timestamp)" >> "$RESULT_LOG"

  # Step 3: Stop Worker 2 (Kafka node)
  log "Persistence: Stopping Worker 2 (Kafka node)"
  echo "T_KAFKA_STOP=$(timestamp)" >> "$RESULT_LOG"
  docker stop "${CLUSTER_NAME}-worker2"

  echo "Waiting 60s for failure propagation..."
  sleep 60

  # Step 4: Recover Worker 2
  log "Persistence: Recovering Worker 2"
  echo "T_KAFKA_RECOVER=$(timestamp)" >> "$RESULT_LOG"
  docker start "${CLUSTER_NAME}-worker2"

  wait_node_ready "${CLUSTER_NAME}-worker2" 300
  wait_pods_ready 300

  # Step 5: Wait for Kafka consumer to catch up
  echo "Waiting 30s for Kafka consumer to reprocess..."
  sleep 30

  echo "T_COMPLETE=$(timestamp)" >> "$RESULT_LOG"

  log "Persistence: Test complete"
  cat "$RESULT_LOG"
  echo ""
  echo "Verify: Check DB issued count matches expected via app API or DB query."
  echo "  curl $APP_URL/actuator/metrics/coupons.issued.count"
}

# ===== Main =====
usage() {
  echo "Usage: $0 {preflight|d1|d2|persistence|all}"
  echo ""
  echo "  preflight    - Clean up cluster state and verify readiness"
  echo "  d1           - Experiment D-1: Infra node failure"
  echo "  d2           - Experiment D-2: App node failure"
  echo "  persistence  - Data persistence verification"
  echo "  all          - Run preflight + d1 + d2 + persistence"
  exit 1
}

case "${1:-all}" in
  preflight)
    preflight
    ;;
  d1)
    preflight
    run_d1
    ;;
  d2)
    preflight
    run_d2
    ;;
  persistence)
    preflight
    run_persistence
    ;;
  all)
    preflight
    run_d1
    echo ""
    echo "Cooldown 60s between experiments..."
    sleep 60
    preflight
    run_d2
    echo ""
    echo "Cooldown 60s before persistence test..."
    sleep 60
    preflight
    run_persistence
    log "All Phase 4 experiments complete!"
    echo "Results: $RESULTS_DIR/"
    echo "k6 results: $LOG_DIR/phase4-resilience-*-result.json"
    ;;
  *)
    usage
    ;;
esac
