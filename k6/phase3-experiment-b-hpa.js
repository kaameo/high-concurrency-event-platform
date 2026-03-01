import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const issuedCounter = new Counter('coupons_issued');
const duplicateCounter = new Counter('coupons_duplicate');
const soldOutCounter = new Counter('coupons_sold_out');
const rateLimitedCounter = new Counter('coupons_rate_limited');
const errorCounter = new Counter('errors');
const successRate = new Rate('success_rate');
const issuanceLatency = new Trend('issuance_latency', true);

// Configuration
const COUPON_EVENT_ID = '019577a0-0000-7000-8000-000000000001';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:30080';
const HPA_CONFIG = __ENV.HPA_CONFIG || 'cpu';

const ENDPOINT = `${BASE_URL}/api/v1/coupons/issue`;

/*
 * Experiment B: HPA Auto-scaling Spike Test
 *
 * Spike pattern: 0 → 10,000 VU in 30s, sustain 5 min
 *
 * Usage:
 *   k6 run --env HPA_CONFIG=cpu    k6/phase3-experiment-b-hpa.js
 *   k6 run --env HPA_CONFIG=memory k6/phase3-experiment-b-hpa.js
 *   k6 run --env HPA_CONFIG=custom k6/phase3-experiment-b-hpa.js
 */
export const options = {
  stages: [
    { duration: '10s',  target: 100 },
    { duration: '30s',  target: 10000 },
    { duration: '300s', target: 10000 },
    { duration: '30s',  target: 0 },
  ],
  thresholds: {
    http_req_duration: [{ threshold: 'p(95)<30000', abortOnFail: false }],
    success_rate: [{ threshold: 'rate>0.50', abortOnFail: false }],
  },
};

export default function () {
  const idempotencyKey = uuidv4();
  const userId = Math.floor(Math.random() * 10000000) + 1;

  const payload = JSON.stringify({
    couponEventId: COUPON_EVENT_ID,
    userId: userId,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  };

  const res = http.post(ENDPOINT, payload, params);

  issuanceLatency.add(res.timings.duration);

  if (res.status === 200 || res.status === 202) {
    issuedCounter.add(1);
    successRate.add(true);
  } else if (res.status === 409) {
    duplicateCounter.add(1);
    successRate.add(true);
  } else if (res.status === 410) {
    soldOutCounter.add(1);
    successRate.add(true);
  } else if (res.status === 429) {
    rateLimitedCounter.add(1);
    successRate.add(true);
  } else {
    errorCounter.add(1);
    successRate.add(false);
  }

  check(res, {
    'status is expected': (r) =>
      [200, 202, 409, 410, 429].includes(r.status),
    'response has body': (r) => r.body && r.body.length > 0,
  });

  sleep(0.05);
}

function safe(data, metric, key) {
  try {
    const v = data.metrics[metric].values[key];
    return v != null ? v.toFixed(2) + 'ms' : 'N/A';
  } catch (e) {
    return 'N/A';
  }
}

export function handleSummary(data) {
  const configLabel = {
    cpu: 'B-1 CPU 50%',
    memory: 'B-2 Memory 70%',
    custom: 'B-3 HTTP RPS 1000',
  }[HPA_CONFIG] || HPA_CONFIG;

  const summary = {
    test: `Experiment B HPA — ${configLabel}`,
    hpa_config: HPA_CONFIG,
    endpoint: ENDPOINT,
    timestamp: new Date().toISOString(),
    vus_max: data.metrics.vus_max && data.metrics.vus_max.values ? data.metrics.vus_max.values.max : 'N/A',
    total_requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
    rps: data.metrics.http_reqs && data.metrics.http_reqs.values.rate != null ? data.metrics.http_reqs.values.rate.toFixed(2) : 0,
    latency: {
      avg: safe(data, 'http_req_duration', 'avg'),
      p50: safe(data, 'http_req_duration', 'p(50)'),
      p90: safe(data, 'http_req_duration', 'p(90)'),
      p95: safe(data, 'http_req_duration', 'p(95)'),
      p99: safe(data, 'http_req_duration', 'p(99)'),
      max: safe(data, 'http_req_duration', 'max'),
    },
    coupons_issued: data.metrics.coupons_issued ? data.metrics.coupons_issued.values.count : 0,
    coupons_duplicate: data.metrics.coupons_duplicate ? data.metrics.coupons_duplicate.values.count : 0,
    coupons_sold_out: data.metrics.coupons_sold_out ? data.metrics.coupons_sold_out.values.count : 0,
    coupons_rate_limited: data.metrics.coupons_rate_limited ? data.metrics.coupons_rate_limited.values.count : 0,
    errors: data.metrics.errors ? data.metrics.errors.values.count : 0,
    success_rate: data.metrics.success_rate ? (data.metrics.success_rate.values.rate * 100).toFixed(2) + '%' : 'N/A',
  };

  const filename = `k6/phase3-experiment-b-${HPA_CONFIG}-result.json`;

  return {
    [filename]: JSON.stringify(summary, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const configLabel = {
    cpu: 'B-1 CPU 50%',
    memory: 'B-2 Memory 70%',
    custom: 'B-3 HTTP RPS 1000',
  }[HPA_CONFIG] || HPA_CONFIG;

  const lines = [
    '',
    '╔══════════════════════════════════════════════════════╗',
    `║   Experiment B HPA — ${configLabel.padEnd(31)}║`,
    '╚══════════════════════════════════════════════════════╝',
    '',
  ];

  if (data.metrics.http_reqs) {
    lines.push(`  Total Requests:   ${data.metrics.http_reqs.values.count}`);
    lines.push(`  RPS (avg):        ${data.metrics.http_reqs.values.rate.toFixed(2)}`);
  }
  if (data.metrics.http_req_duration) {
    lines.push(`  Latency avg:      ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms`);
    lines.push(`  Latency p95:      ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)}ms`);
    lines.push(`  Latency p99:      ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)}ms`);
    lines.push(`  Latency max:      ${data.metrics.http_req_duration.values.max.toFixed(2)}ms`);
  }
  if (data.metrics.coupons_issued) {
    lines.push(`  Coupons Issued:   ${data.metrics.coupons_issued.values.count}`);
  }
  if (data.metrics.coupons_sold_out) {
    lines.push(`  Sold Out:         ${data.metrics.coupons_sold_out.values.count}`);
  }
  if (data.metrics.coupons_rate_limited) {
    lines.push(`  Rate Limited:     ${data.metrics.coupons_rate_limited.values.count}`);
  }
  if (data.metrics.errors) {
    lines.push(`  Errors:           ${data.metrics.errors.values.count}`);
  }
  lines.push('');

  return lines.join('\n');
}
