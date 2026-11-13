[![StockFlow CI](https://github.com/skyn456-fumika/StockFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/skyn456-fumika/StockFlow/actions/workflows/ci.yml)

# StockFlow

**Spring Boot 기반 주문·재고 관리 및 이벤트 기반 메시징 시스템**

[![StockFlow CI](https://github.com/skyn456-fumika/StockFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/skyn456-fumika/StockFlow/actions/workflows/ci.yml)

## 1. 프로젝트 소개

StockFlow는 주문 및 재고 관리 시스템을 기반으로, 동시성 제어와 이벤트 기반 비동기 처리의 안정성을 검증하기 위해 개발한 Java 백엔드 개인 프로젝트입니다.

단순 CRUD를 넘어 데이터 정합성, 중복 요청 방지, 메시지 발행 실패 복구, 운영 모니터링 및 자동화된 테스트 환경을 구현하는 것을 목표로 합니다.

## 2. 기술 스택

| 구분 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.2 |
| ORM | Spring Data JPA / Hibernate |
| Database | PostgreSQL 17 |
| Cache | Redis 7 |
| Message Broker | RabbitMQ 4.3 |
| Migration | Flyway |
| Monitoring | Spring Boot Actuator, Micrometer |
| Testing | JUnit 5, Testcontainers, Mockito |
| Build | Gradle 9.8.2 |
| CI | GitHub Actions |
| Infrastructure | Docker, Docker Compose |

## 3. 주요 기능

### 상품 및 재고 관리

- 상품 등록, 조회 및 수정
- 재고 입고·출고 처리
- 재고 변경 이력 관리
- 트랜잭션을 통한 재고 데이터 정합성 보장

### 주문 관리

- 다중 상품 주문 생성
- 주문 취소 및 재고 복구
- 멱등성 키를 활용한 중복 주문 방지
- PostgreSQL 락 기반 동시성 제어
- 재고 부족 시 트랜잭션 롤백

### Redis 캐싱

- 상품 조회 결과 캐싱
- 상품 정보 변경 시 캐시 무효화
- 트랜잭션 커밋 이후 캐시 삭제
- Redis 장애 발생 시 DB 조회로 대체

### RabbitMQ 비동기 이벤트 처리

- Transactional Outbox 패턴을 활용한 이벤트 저장 및 발행
- Inbox 패턴을 활용한 중복 이벤트 처리 방지
- RabbitMQ Publisher Confirm 처리
- Dead Letter Queue(DLQ)를 통한 실패 메시지 분리
- 메시지 재전달 및 Consumer 재시도 처리

### Outbox 신뢰성 개선

- `FOR UPDATE SKIP LOCKED` 기반 중복 발행 방지
- 다중 스케줄러 환경에서 이벤트 처리
- Exponential Backoff 기반 재시도
- 실패 횟수 및 다음 재시도 시간 저장
- 발행 실패 이후 복구 처리

### 운영 모니터링

- Outbox 상태별 집계 API
- 장기 대기 및 반복 실패 이벤트 감지
- 관리자 API 토큰 인증
- Spring Boot Actuator Health Check
- Micrometer 기반 발행 성공·실패 Counter 및 처리 시간 Timer

## 4. 시스템 아키텍처

```text
                  Client
                    |
                    v
             Spring Boot API
                    |
          +---------+---------+
          |                   |
          v                   v
    Product / Inventory    Order Service
          |                   |
          v                   v
     PostgreSQL         Order + Outbox
          |                   |
          v                   v
     Redis Cache       Outbox Scheduler
                              |
                              v
                           RabbitMQ
                              |
                              v
                       Event Consumer
                              |
                              v
                        Inbox 저장

             Actuator / Micrometer
                      |
                      v
                 운영 모니터링
```

주문과 Outbox 이벤트는 동일한 DB 트랜잭션에서 저장하며, 별도 스케줄러가 미발행 이벤트를 조회하여 RabbitMQ로 전달합니다.

## 5. 주요 기술적 고려사항

### 5.1 동시 주문과 재고 정합성

동시에 여러 주문 요청이 발생하더라도 재고가 음수가 되거나 중복 주문이 생성되지 않도록 트랜잭션과 DB 락을 활용했습니다.

### 5.2 Transactional Outbox 패턴

DB 트랜잭션과 RabbitMQ 메시지 발행 사이의 불일치 문제를 줄이기 위해 Outbox 테이블에 이벤트를 먼저 저장합니다.

이후 스케줄러가 미발행 이벤트를 조회하여 메시지를 발행하고, 실패한 이벤트는 재시도 대상으로 관리합니다.

### 5.3 다중 스케줄러 환경의 동시성

`FOR UPDATE SKIP LOCKED`를 활용하여 이미 다른 트랜잭션이 처리 중인 이벤트를 건너뛰도록 구현했습니다.

### 5.4 장애 복구와 재시도

발행 실패 시 실패 횟수와 다음 재시도 시각을 기록하며, Exponential Backoff를 적용하여 재시도 간격을 조절합니다.

### 5.5 통합 테스트 환경 개선

Testcontainers를 활용하여 PostgreSQL, Redis, RabbitMQ를 테스트 환경에서 실행합니다.

테스트 격리를 유지할 수 있는 일부 클래스에는 공통 컨테이너를 적용하고, 메시지 발행 및 큐 상태에 민감한 테스트는 독립 컨테이너를 유지했습니다.

## 6. 테스트

JUnit 5와 Testcontainers 기반으로 단위 테스트 및 통합 테스트를 분리했습니다.

**검증 결과 (2026년 11월 기준)**

- 통합 테스트 41개 클래스
- 통합 테스트 메서드 112개 통과
- PostgreSQL, Redis, RabbitMQ 기반 통합 테스트
- GitHub Actions CI 통과
- CI 실행 시간 약 3분 31초

### 로컬 실행

단위 테스트:

```bash
./gradlew unitTest
```

통합 테스트:

```bash
./gradlew integrationTest
```

Windows PowerShell에서는 `./gradlew.bat`을 사용할 수 있습니다.

## 7. CI/CD

GitHub Actions를 사용하여 `main` 브랜치 Push 및 Pull Request 시 다음 작업을 자동으로 수행합니다.

1. Java 21 환경 구성
2. Gradle 빌드 환경 구성
3. 단위 테스트 실행
4. Testcontainers 기반 통합 테스트 실행
5. 테스트 결과 보고서 업로드

현재 자동화 범위는 **CI(빌드 및 테스트 검증)**이며, 운영 환경 자동 배포는 포함하지 않습니다.

## 8. Docker 실행 방법

StockFlow는 Docker Compose를 사용하여 Spring Boot 애플리케이션과 PostgreSQL, Redis, RabbitMQ를 함께 실행할 수 있습니다.

### 사전 준비

- Docker Desktop 또는 Docker Engine
- Docker Compose

### 환경변수 설정

프로젝트 루트에 `.env` 파일을 생성하고 기존 Compose 환경에 맞는 인증정보를 설정합니다.

```dotenv
POSTGRES_USER=stockflow
POSTGRES_PASSWORD=<DB_PASSWORD>

RABBITMQ_USER=<RABBITMQ_USERNAME>
RABBITMQ_PASSWORD=<RABBITMQ_PASSWORD>
```

`.env` 파일은 Git에 포함되지 않습니다.

### Docker 이미지 빌드

```bash
docker build -t stockflow-app:local .
```

### 전체 서비스 실행

```bash
docker compose -f compose.yml up -d
```

### 컨테이너 상태 확인

```bash
docker compose -f compose.yml ps
```

Spring Boot, PostgreSQL, Redis, RabbitMQ 컨테이너의 상태를 확인합니다.

### API 동작 확인

```bash
curl http://localhost:8080/api/products
```

### Spring Boot Health Check

Actuator는 관리 포트 `8081`에서 실행되며, Docker 컨테이너 내부에서 확인할 수 있습니다.

```bash
docker compose -f compose.yml exec app \
  wget -qO- http://127.0.0.1:8081/actuator/health
```

정상적인 경우 다음과 유사한 응답을 반환합니다.

```json
{
  "groups": ["liveness", "readiness"],
  "status": "UP"
}
```

### 로그 확인

```bash
docker compose -f compose.yml logs -f app
```

### 서비스 종료

```bash
docker compose -f compose.yml down
```

데이터베이스 볼륨을 유지하기 위해 일반적인 종료 시에는 `down -v`를 사용하지 않습니다.

---

## 9. 향후 개선 계획

- 배포 환경의 비밀정보 관리 강화
- CI/CD 자동 배포 파이프라인 구축
- 운영 모니터링 및 알림 체계 확장
- 테스트 격리 및 실행 시간 추가 최적화

## 10. Repository

GitHub: https://github.com/skyn456-fumika/StockFlow
