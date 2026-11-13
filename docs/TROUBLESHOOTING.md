# StockFlow 기술적 문제 해결 사례

## 1. 동시 주문 시 재고 정합성 확보

### 문제

동일한 상품에 여러 주문 요청이 동시에 발생하면 재고 차감 과정에서 경합이 발생할 수 있습니다.

### 해결

- PostgreSQL 트랜잭션과 DB 락 적용
- 재고 부족 시 주문 실패 처리
- 멱등성 키를 통한 중복 주문 방지

### 검증 결과

- 초기 재고: 10개
- 동시 주문 요청: 100건
- 주문 성공: 10건
- 재고 부족: 90건
- 최종 재고: 0개
- P95 응답 시간: 311.88ms

**결과:** 테스트한 동시 주문 시나리오에서 초과 판매가 발생하지 않았습니다.

## 2. Redis 장애 시 상품 조회 지연 개선

### 문제

Redis 컨테이너를 중지한 상태에서 상품 상세 조회 API가 약 120초 동안 지연됐습니다.

### 원인 분석

Redis 연결 장애 상황에서 Lettuce의 재연결 시도 및 호스트 이름 확인 실패가 관찰됐습니다.

기존 Redis 설정에는 연결 및 명령 타임아웃이 명시되어 있지 않았습니다.

### 해결

`application.yml`에 타임아웃을 설정했습니다.

```yaml
spring:
  data:
    redis:
      connect-timeout: 1s
      timeout: 2s
```

### 검증 결과

- 개선 전: 약 120초
- 개선 후: 약 4.02초
- 응답 시간 약 96.7% 감소
- Redis 중지 상태에서도 상품 조회 성공

**한계:** Fallback 예외 로그가 직접 확인되지 않았으며, 장애 시 응답 시간을 더욱 단축할 여지가 있습니다.

## 3. RabbitMQ 장애 시 이벤트 유실 방지

### 문제

RabbitMQ가 중단되면 주문 생성과 메시지 발행 사이에 불일치가 발생할 수 있습니다.

### 해결

Transactional Outbox 패턴을 적용했습니다.

- 주문과 Outbox 이벤트를 동일한 DB 트랜잭션으로 저장
- 이벤트 발행은 별도 스케줄러에서 수행
- 발행 실패 시 `PENDING` 상태 유지
- Exponential Backoff 기반 재시도
- `FOR UPDATE SKIP LOCKED`로 중복 처리 경합 완화

### 검증 결과

RabbitMQ 중지 상태에서 주문 ID 22를 생성했습니다.

- 주문 생성 성공
- 재고 10개에서 9개로 차감
- Outbox 이벤트 `PENDING` 상태 유지
- 재시도 횟수 7회 기록
- RabbitMQ 복구 후 `PUBLISHED` 전환
- `published_at` 기록 확인

**결과:** RabbitMQ 장애 중 저장된 이벤트가 브로커 복구 후 발행됐습니다.

## 4. RabbitMQ Consumer 미등록 문제 해결

### 문제

RabbitMQ 복구 후 Outbox 큐에 메시지 22건이 대기했지만, Consumer 연결 수가 0명이었습니다.

### 원인

두 Consumer 클래스의 `@ConditionalOnProperty` 활성화 조건에 필요한 환경변수가 Docker Compose에 설정되지 않았습니다.

### 해결

```yaml
STOCKFLOW_ORDER_CONSUMER_ENABLED: "true"
STOCKFLOW_OUTBOX_CONSUMER_ENABLED: "true"
```

환경변수를 설정한 뒤 Spring Boot 컨테이너를 재생성했습니다.

### 검증 결과

- Order Consumer: 0명 → 1명
- Outbox Consumer: 0명 → 1명
- Outbox 큐 대기 메시지: 22건 → 0건
- `processed_order_events`에 주문 ID 22의 처리 기록 생성

**결과:** 메시지 발행뿐 아니라 Consumer 처리까지 정상 완료된 것을 확인했습니다.

## 5. 통합 테스트 환경 최적화

### 문제

PostgreSQL, Redis, RabbitMQ를 사용하는 통합 테스트에서 컨테이너의 반복적인 생성과 종료는 테스트 실행 시간을 증가시킬 수 있습니다.

### 해결

- `SharedContainers`를 통한 테스트 컨테이너 재사용
- 테스트 격리가 가능한 클래스에 공통 컨테이너 적용
- 큐 상태 및 메시지에 민감한 테스트는 독립 컨테이너 유지

### 검증 결과

- 통합 테스트 41개 클래스
- 통합 테스트 메서드 112개 통과
- GitHub Actions CI 성공

**결과:** 테스트 실행 효율과 격리 요구사항을 함께 고려한 테스트 환경을 구성했습니다.
