import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Rate, Trend } from 'k6/metrics';

const issuedCounter = new Counter('coupons_issued');
const duplicateCounter = new Counter('coupons_duplicate');
const soldOutCounter = new Counter('coupons_sold_out');
const rateLimitedCounter = new Counter('coupons_rate_limited');
const errorCounter = new Counter('errors');
const successRate = new Rate('success_rate');
const issuanceLatency = new Trend('issuance_latency', true);

const COUPON_EVENT_ID = '019577a0-0000-7000-8000-000000000001';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ENDPOINT = `${BASE_URL}/api/v1/coupons/issue`;
const CONFIG = __ENV.CONFIG || 'C1'; // C1, C2, C3

/*
 * Experiment C: Kafka Partition Tuning — Consumer 병렬성과 처리량 관계
 *
 * Usage:
 *   k6 run --env CONFIG=C1 k6/phase3-experiment-c-partition.js  # 1 partition, concurrency 1
 *   k6 run --env CONFIG=C2 k6/phase3-experiment-c-partition.js  # 3 partitions, concurrency 3
 *   k6 run --env CONFIG=C3 k6/phase3-experiment-c-partition.js  # 10 partitions, concurrency 10
 *
 * Before each run:
 *   1. Delete topic: docker exec kafka kafka-topics --delete --topic coupon-issue --bootstrap-server localhost:9092
 *   2. Set env vars:
 *      C1: KAFKA_COUPON_PARTITIONS=1 KAFKA_COUPON_CONSUMER_CONCURRENCY=1
 *      C2: KAFKA_COUPON_PARTITIONS=3 KAFKA_COUPON_CONSUMER_CONCURRENCY=3
 *      C3: KAFKA_COUPON_PARTITIONS=10 KAFKA_COUPON_CONSUMER_CONCURRENCY=10
 *   3. Restart application
 *   4. Reset DB: TRUNCATE coupon_issues; and Redis: FLUSHDB
 *
 * Spike pattern (same as Experiment A):
 *   Warm-up    (10s, 10 VUs)
 *   Ramp-up    (5s,  10 → 500 VUs)
 *   Sustained  (60s, 500 VUs)
 *   Peak       (5s,  500 → 1000 VUs)
 *   Peak hold  (60s, 1000 VUs)
 *   Cool-down  (10s, 0 VUs)
 */
export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '5s',  target: 500 },
    { duration: '60s', target: 500 },
    { duration: '5s',  target: 1000 },
    { duration: '60s', target: 1000 },
    { duration: '10s', target: 0 },
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

export function handleSummary(data) {
  const configLabel = {
    C1: '1 partition / concurrency 1',
    C2: '3 partitions / concurrency 3',
    C3: '10 partitions / concurrency 10',
  }[CONFIG] || CONFIG;

  const summary = {
    test: `Experiment C Partition Tuning — ${configLabel}`,
    config: CONFIG,
    endpoint: ENDPOINT,
    timestamp: new Date().toISOString(),
    vus_max: data.metrics.vus_max && data.metrics.vus_max.values ? data.metrics.vus_max.values.max : 'N/A',
    total_requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
    rps: data.metrics.http_reqs ? data.metrics.http_reqs.values.rate.toFixed(2) : '0',
    latency: data.metrics.http_req_duration ? {
      avg: data.metrics.http_req_duration.values.avg.toFixed(2) + 'ms',
      med: data.metrics.http_req_duration.values.med.toFixed(2) + 'ms',
      p90: data.metrics.http_req_duration.values['p(90)'].toFixed(2) + 'ms',
      p95: data.metrics.http_req_duration.values['p(95)'].toFixed(2) + 'ms',
      p99: data.metrics.http_req_duration.values['p(99)'].toFixed(2) + 'ms',
      max: data.metrics.http_req_duration.values.max.toFixed(2) + 'ms',
    } : 'N/A',
    coupons_issued: data.metrics.coupons_issued ? data.metrics.coupons_issued.values.count : 0,
    coupons_duplicate: data.metrics.coupons_duplicate ? data.metrics.coupons_duplicate.values.count : 0,
    coupons_sold_out: data.metrics.coupons_sold_out ? data.metrics.coupons_sold_out.values.count : 0,
    coupons_rate_limited: data.metrics.coupons_rate_limited ? data.metrics.coupons_rate_limited.values.count : 0,
    errors: data.metrics.errors ? data.metrics.errors.values.count : 0,
    success_rate: data.metrics.success_rate ? (data.metrics.success_rate.values.rate * 100).toFixed(2) + '%' : 'N/A',
  };

  const filename = `k6/phase3-experiment-c-${CONFIG.toLowerCase()}-result.json`;

  return {
    [filename]: JSON.stringify(summary, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const configLabel = {
    C1: '1 part / 1 consumer',
    C2: '3 part / 3 consumers',
    C3: '10 part / 10 consumers',
  }[CONFIG] || CONFIG;

  const lines = [
    '',
    '╔══════════════════════════════════════════════════════╗',
    `║   Experiment C — ${configLabel.padEnd(34)}║`,
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
