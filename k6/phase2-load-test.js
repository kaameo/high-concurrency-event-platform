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

// Test configuration
const COUPON_EVENT_ID = '019577a0-0000-7000-8000-000000000001';
const BASE_URL = 'http://localhost:8080';

/*
 * Phase 2 Load Test: Redis + Kafka 비동기 파이프라인 성능 측정
 *
 * Baseline 대비 동일한 부하 프로파일로 비교 측정
 */
export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '30s', target: 50 },
    { duration: '60s', target: 50 },
    { duration: '30s', target: 100 },
    { duration: '60s', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    success_rate: ['rate>0.95'],
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

  const res = http.post(`${BASE_URL}/api/v1/coupons/issue`, payload, params);

  issuanceLatency.add(res.timings.duration);

  if (res.status === 202) {
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
      [202, 409, 410, 429].includes(r.status),
    'response has body': (r) => r.body && r.body.length > 0,
  });

  sleep(0.1);
}

export function handleSummary(data) {
  const summary = {
    test: 'Phase 2 — Redis + Kafka Async Pipeline',
    timestamp: new Date().toISOString(),
    vus_max: data.metrics.vus_max ? data.metrics.vus_max.values.max : 'N/A',
    total_requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
    rps: data.metrics.http_reqs ? data.metrics.http_reqs.values.rate.toFixed(2) : 0,
    latency: {
      avg: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.avg.toFixed(2) + 'ms' : 'N/A',
      p50: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(50)'].toFixed(2) + 'ms' : 'N/A',
      p90: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(90)'].toFixed(2) + 'ms' : 'N/A',
      p95: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(95)'].toFixed(2) + 'ms' : 'N/A',
      p99: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(99)'].toFixed(2) + 'ms' : 'N/A',
      max: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.max.toFixed(2) + 'ms' : 'N/A',
    },
    coupons_issued: data.metrics.coupons_issued ? data.metrics.coupons_issued.values.count : 0,
    coupons_duplicate: data.metrics.coupons_duplicate ? data.metrics.coupons_duplicate.values.count : 0,
    coupons_sold_out: data.metrics.coupons_sold_out ? data.metrics.coupons_sold_out.values.count : 0,
    coupons_rate_limited: data.metrics.coupons_rate_limited ? data.metrics.coupons_rate_limited.values.count : 0,
    errors: data.metrics.errors ? data.metrics.errors.values.count : 0,
    success_rate: data.metrics.success_rate ? (data.metrics.success_rate.values.rate * 100).toFixed(2) + '%' : 'N/A',
  };

  return {
    'k6/phase2-result.json': JSON.stringify(summary, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const lines = [
    '',
    '╔══════════════════════════════════════════════════════╗',
    '║   Phase 2 — Redis + Kafka Async Pipeline             ║',
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
