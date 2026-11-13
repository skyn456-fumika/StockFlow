
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,
  duration: '30s',
};

const BASE_URL =
  __ENV.BASE_URL || 'http://host.docker.internal:8080';


export default function () {
  const response = http.get(`${BASE_URL}/api/products/1`);

  if (response.status !== 200) {
    console.error(
      `HTTP FAILED: status=${response.status}, ` +
      `duration=${response.timings.duration}ms, ` +
      `body=${String(response.body).slice(0, 300)}`
    );
  }

  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'response under 1000ms': (r) => r.timings.duration < 1000,
  });

  sleep(1);
}
