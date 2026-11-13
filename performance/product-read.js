
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '20s',
};

const BASE_URL =
  __ENV.BASE_URL || 'http://host.docker.internal:8080';

export default function () {
  const response = http.get(
    `${BASE_URL}/api/products?page=0&size=10`
  );

  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'response under 1000ms': (r) => r.timings.duration < 1000,
  });

  sleep(1);
}
