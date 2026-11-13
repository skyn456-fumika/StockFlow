
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const created = new Counter('orders_created');
const conflict = new Counter('orders_conflict');
const unexpected = new Counter('orders_unexpected');

export const options = {
  scenarios: {
    concurrent_orders: {
      executor: 'per-vu-iterations',
      vus: 100,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    orders_created: ['count==10'],
    orders_conflict: ['count==90'],
  },
};

const BASE_URL =
  __ENV.BASE_URL || 'http://host.docker.internal:8080';

const PRODUCT_ID = Number(__ENV.PRODUCT_ID);
const RUN_ID = __ENV.RUN_ID || 'local-test';

export default function () {
  if (!PRODUCT_ID) {
    throw new Error('PRODUCT_ID is required');
  }

  // VU마다 서로 다른 멱등성 키 사용
  const idempotencyKey =
    `k6-order-${RUN_ID}-${__VU}`;

  const payload = JSON.stringify({
    items: [
      {
        productId: PRODUCT_ID,
        quantity: 1,
      },
    ],
  });

  const response = http.post(
    `${BASE_URL}/api/orders`,
    payload,
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      responseCallback: http.expectedStatuses(201, 409),
    }
  );

  if (response.status === 201) {
    created.add(1);
  } else if (response.status === 409) {
    conflict.add(1);
  } else {
    unexpected.add(1);

    console.error(
      `VU=${__VU}, status=${response.status}, ` +
      `body=${String(response.body).slice(0, 300)}`
    );
  }

  check(response, {
    'HTTP 201 or 409': (r) =>
      r.status === 201 || r.status === 409,

    'response under 3000ms': (r) =>
      r.timings.duration < 3000,
  });
}
