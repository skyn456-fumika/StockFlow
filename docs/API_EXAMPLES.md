# StockFlow API 사용 가이드

## 1. 개요

StockFlow는 상품 관리, 재고 입출고, 주문 생성 및 취소 기능을 REST API로 제공합니다.

**기본 URL**

```text
http://localhost:8080
```

모든 JSON 요청에는 다음 헤더를 사용합니다.

```http
Content-Type: application/json
```

예시의 상품 ID와 주문 ID는 실행 환경에 따라 달라집니다.

## 2. 상품 API

### 2.1 상품 등록

**POST /api/products**

요청:

```json
{
  "name": "테스트 상품",
  "price": 10000,
  "initialQuantity": 10
}
```

정상 응답: `201 Created`

응답 예시:

```json
{
  "productId": 1
}
```

요청 검증 조건:

- 상품명: 필수, 최대 100자
- 가격: 필수, 0 이상, 소수점 이하 최대 2자리
- 초기 재고: 필수, 0 이상

### 2.2 상품 상세 조회

**GET /api/products/{productId}**

요청 예시:

```http
GET /api/products/1
```

정상 응답: `200 OK`

```json
{
  "productId": 1,
  "name": "테스트 상품",
  "price": 10000.00,
  "status": "ACTIVE",
  "quantity": 10
}
```

상품 기본 정보에는 Redis 캐시가 사용될 수 있으며, 재고 수량은 PostgreSQL에서 조회합니다.

### 2.3 상품 목록 조회

**GET /api/products?page=0&size=10**

| 파라미터 | 설명 |
|---|---|
| `page` | 페이지 번호, 0부터 시작 |
| `size` | 페이지당 조회 개수 |

상품 목록을 페이지 단위로 조회합니다.

## 3. 재고 API

### 3.1 재고 입고

**POST /api/inventories/{productId}/increase**

요청:

```json
{
  "amount": 5
}
```

`amount`는 1 이상의 정수여야 합니다.

### 3.2 재고 출고

**POST /api/inventories/{productId}/decrease**

요청:

```json
{
  "amount": 3
}
```

재고 출고 시 재고 부족 여부를 검증합니다.

### 3.3 재고 변경 이력 조회

**GET /api/inventories/{productId}/histories**

재고 입출고에 따른 변경 이력을 조회합니다.

## 4. 주문 API

### 4.1 주문 생성

**POST /api/orders**

주문 생성 시 `Idempotency-Key` 헤더가 필수입니다.

요청:

```http
POST /api/orders
Content-Type: application/json
Idempotency-Key: example-order-001
```

```json
{
  "items": [
    {
      "productId": 1,
      "quantity": 2
    }
  ]
}
```

정상 응답: `201 Created`

```http
Location: /api/orders/1
```

```json
{
  "orderId": 1
}
```

주문 생성 시 재고가 차감되며, 주문 이벤트가 Outbox 테이블에 함께 저장됩니다.

**요청 검증 조건**

- `Idempotency-Key`: 필수, 공백 불가, 최대 100자
- `items`: 최소 1개
- `productId`: 필수
- `quantity`: 필수, 1 이상

### 4.2 주문 상세 조회

**GET /api/orders/{orderId}**

정상 응답: `200 OK`

응답 형식 예시:

```json
{
  "orderId": 1,
  "status": "CREATED",
  "totalAmount": 20000.00,
  "createdAt": "2026-11-13T14:00:00",
  "items": [
    {
      "productId": 1,
      "productName": "테스트 상품",
      "quantity": 2,
      "unitPrice": 10000.00,
      "subtotal": 20000.00
    }
  ]
}
```

위 JSON은 DTO 구조를 설명하기 위한 예시이며, 실제 주문 상태와 생성 시각은 요청 결과에 따라 달라집니다.

### 4.3 주문 취소

**POST /api/orders/{orderId}/cancel**

요청 예시:

```http
POST /api/orders/1/cancel
```

정상 응답: `204 No Content`

주문 취소 시 재고 복구 로직이 실행됩니다.

## 5. PowerShell 실행 예시

Windows PowerShell에서 상품 등록부터 주문 취소까지 순서대로 실행할 수 있습니다.

### 5.1 상품 등록

```powershell
$product = Invoke-RestMethod `
    -Uri "http://localhost:8080/api/products" `
    -Method Post `
    -ContentType "application/json" `
    -Body '{"name":"API Guide Test","price":10000,"initialQuantity":10}'

$productId = $product.productId
```

### 5.2 상품 조회

```powershell
Invoke-RestMethod "http://localhost:8080/api/products/$productId"
```

### 5.3 주문 생성

```powershell
$order = Invoke-RestMethod `
    -Uri "http://localhost:8080/api/orders" `
    -Method Post `
    -ContentType "application/json" `
    -Headers @{
        "Idempotency-Key" = [guid]::NewGuid().ToString()
    } `
    -Body "{`"items`":[{`"productId`":$productId,`"quantity`":2}]}"

$orderId = $order.orderId
```

### 5.4 주문 상세 조회

```powershell
Invoke-RestMethod "http://localhost:8080/api/orders/$orderId"
```

### 5.5 주문 취소

```powershell
Invoke-RestMethod `
    -Uri "http://localhost:8080/api/orders/$orderId/cancel" `
    -Method Post
```

### 5.6 취소 후 재고 조회

```powershell
Invoke-RestMethod "http://localhost:8080/api/products/$productId"
```

정상적으로 취소되고 재고가 복구되면 초기 수량인 10개가 다시 확인됩니다.

## 6. 관련 문서

- [성능 테스트 결과](../performance/RESULTS.md)
- [장애 복구 검증](FAILURE_RECOVERY.md)
- [기술적 문제 해결 사례](TROUBLESHOOTING.md)
