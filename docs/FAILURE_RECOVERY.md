# StockFlow 장애 복구 검증

## 1. Redis 장애 대응

**테스트 시나리오:** Redis 컨테이너를 중지한 상태에서 상품 상세 조회 API 실행

- Redis 캐시 활성화 설정: `true`
- Redis 중지 상태에서 상품 조회 성공
- 타임아웃 설정 전 응답 시간: 약 120초
- 타임아웃 설정 후 응답 시간: 약 4.02초
- 응답 시간 약 96.7% 감소
- Redis 복구 후 캐시 키 존재 확인 (`EXISTS = 1`)
- 캐시 TTL 확인 (`221초`)

**개선 사항**

`application.yml`에 Redis 연결 및 명령 타임아웃을 명시하여 장애 상황에서 장시간 대기하는 문제를 완화했습니다.

단, Fallback 예외 처리 로그는 직접 확인되지 않았으며, 4초 수준의 장애 응답 시간은 추가 개선 여지가 있습니다.

## 2. RabbitMQ 장애 및 Outbox 복구

**테스트 시나리오:** RabbitMQ 컨테이너 중지 상태에서 신규 주문 생성

### 장애 발생 중

- 상품 ID: `4`
- 주문 ID: `22`
- 재고: `10 → 9`
- Outbox 이벤트 상태: `PENDING`
- 발행 재시도 횟수: `7`
- 이벤트 발행 완료 시각: `NULL`

RabbitMQ 중지 상태에서도 주문 및 재고 변경이 정상적으로 처리되고, 이벤트가 PostgreSQL에 보존됐습니다.

### RabbitMQ 복구 후

- Outbox 상태: `PUBLISHED`
- `published_at` 기록 확인
- `next_retry_at` 초기화 확인
- 이벤트 UUID 유지

RabbitMQ 복구 후 재시도 스케줄러에 의해 미발행 이벤트가 정상 발행됐습니다.

## 3. Consumer 미등록 문제 해결

RabbitMQ 복구 이후 Outbox 큐에 메시지 22건이 대기했지만 Consumer 연결 수는 0명이었습니다.

**원인**

두 Consumer 클래스에 설정된 `@ConditionalOnProperty` 활성화 조건에 필요한 환경변수가 Docker Compose에 지정되지 않았습니다.

**해결**

```yaml
STOCKFLOW_ORDER_CONSUMER_ENABLED: "true"
STOCKFLOW_OUTBOX_CONSUMER_ENABLED: "true"
```

환경변수 적용 후 Spring Boot 컨테이너를 재생성했습니다.

**검증 결과**

- Order Consumer: 1명
- Outbox Consumer: 1명
- Outbox 큐 대기 메시지: 22건 → 0건
- 주문 ID 22의 처리 기록 생성 확인

처리된 이벤트 UUID:

`2b86fcd1-54ae-4c67-8469-c0193b496b7b`

이벤트는 `processed_order_events` 테이블에 `ORDER_CREATED` 유형으로 기록됐습니다.

## 4. 결론

이번 테스트를 통해 Redis 장애 시 상품 조회가 계속 가능한 상태와, RabbitMQ 장애 중에도 주문 및 Outbox 이벤트가 보존되고 복구 후 발행·소비되는 흐름을 검증했습니다.

추가 개선 과제로는 Redis 장애 시 응답 시간 단축, Consumer 활성화 설정의 기본값 및 배포 환경 검증, 재시도 상태 모니터링이 있습니다.
