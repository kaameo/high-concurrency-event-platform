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
const SCENARIO = __ENV.SCENARIO || 'd1';

const ENDPOINT = `${BASE_URL}/api/v1/coupons/issue`;

/*
 * Phase 4: Resilience & Recovery Test
 *
 * Sustained load at 3,000 VU for 10 minutes.
 * Node failure injected externally during the test.
 *
 * Usage:
 *   k6 run --env SCENARIO=d1 k6/phase4-resilience.js
 *   k6 run --env SCENARIO=d2 k6/phase4-resilience.js
 *   k6 run --env SCENARIO=persistence k6/phase4-resilience.js
 */
export const options = {
  scenarios: {
    sustained_load: {
      executor: 'ramping-vus',
      stages: [
        { duration: '30s',  target: 3000 },
        { duration: '570s', target: 3000 },
        { duration: '30s',  target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_duration: [{ threshold: 'p(95)<60000', abortOnFail: false }],
    success_rate: [{ threshold: 'rate>0.30', abortOnFail: false }],
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
    timeout: '30s',
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
  const scenarioLabel = {
    d1: 'D-1 Infra Node Failure',
    d2: 'D-2 App Node Failure',
    persistence: 'Data Persistence Test',
  }[SCENARIO] || SCENARIO;

  const summary = {
    test: `Phase 4 Resilience — ${scenarioLabel}`,
    scenario: SCENARIO,
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

  const filename = `k6/phase4-resilience-${SCENARIO}-result.json`;

  return {
    [filename]: JSON.stringify(summary, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const scenarioLabel = {
    d1: 'D-1 Infra Node Failure',
    d2: 'D-2 App Node Failure',
    persistence: 'Data Persistence Test',
  }[SCENARIO] || SCENARIO;

  const lines = [
    '',
    '╔══════════════════════════════════════════════════════╗',
    `║   Phase 4 Resilience — ${scenarioLabel.padEnd(28)}║`,
    '╚══════════════════════════════════════════════════════╝',
    '',
  ];

  if (data.metrics.http_reqs) {
    lines.push(`  Total Requests:   ${data.metrics.http_reqs.values.count}`);
    lines.push(`  RPS (avg):        ${data.metrics.http_reqs.values.rate.toFixed(2)}`);
  }
  if (data.metrics.http_req_duration && data.metrics.http_req_duration.values) {
    const v = data.metrics.http_req_duration.values;
    if (v.avg != null) lines.push(`  Latency avg:      ${v.avg.toFixed(2)}ms`);
    if (v['p(95)'] != null) lines.push(`  Latency p95:      ${v['p(95)'].toFixed(2)}ms`);
    if (v['p(99)'] != null) lines.push(`  Latency p99:      ${v['p(99)'].toFixed(2)}ms`);
    if (v.max != null) lines.push(`  Latency max:      ${v.max.toFixed(2)}ms`);
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
