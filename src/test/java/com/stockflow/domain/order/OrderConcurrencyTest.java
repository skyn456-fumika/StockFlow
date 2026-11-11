package com.stockflow.domain.order;

import com.stockflow.domain.inventory.Inventory;
import com.stockflow.domain.inventory.InventoryHistoryRepository;
import com.stockflow.domain.inventory.InventoryRepository;
import com.stockflow.domain.inventory.exception.InsufficientStockException;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.order.exception.IdempotencyConflictException;
import com.stockflow.domain.order.exception.OrderCancellationException;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@Testcontainers
// 동시 주문 테스트
class OrderConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ProductService productService;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    InventoryHistoryRepository historyRepository;

    // OrderService.createOrder()는 
    // PESSIMISTIC_WRITE 락을 사용하고 있으므로,
    // 동시 요청을 직렬화해서 초과 판매를 방지해야 함
    @Test
    void 동시에_20개_주문하면_재고_10개만큼만_성공한다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "동시성 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        long beforeOrderCount = orderRepository.count();

        int requestCount = 20;
        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {

                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "동시 시작 대기 시간 초과"
                        );
                    }

                    try {
                        orderService.createOrder(
                                List.of(new OrderCreateItem(productId, 1))
                        );
                        return true;

                    } catch (InsufficientStockException e) {
                        return false;
                    }
                }));
            }

            // 모든 요청이 출발 준비를 마칠 때까지 대기
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // 20개 요청을 동시에 시작
            start.countDown();

            int successCount = 0;
            int failureCount = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }

            // then
            assertThat(successCount).isEqualTo(10);
            assertThat(failureCount).isEqualTo(10);

            Inventory inventory = inventoryRepository
                    .findByProductId(productId)
                    .orElseThrow();

            assertThat(inventory.getQuantity()).isZero();

            assertThat(orderRepository.count())
                    .isEqualTo(beforeOrderCount + 10);

            // INITIAL 1개 + 주문 출고 10개
            assertThat(historyRepository
                    .findByInventoryIdOrderByIdDesc(inventory.getId()))
                    .hasSize(11);

        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThatCode(() ->
                    executor.awaitTermination(10, TimeUnit.SECONDS)
            ).doesNotThrowAnyException();
        }
    }

    @Test
    void 동시에_같은_주문을_두번_취소해도_재고는_한번만_복구된다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "동시 취소 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 3))
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {

                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 시작 대기 시간 초과");
                    }

                    try {
                        orderService.cancelOrder(orderId);
                        return true;
                    } catch (OrderCancellationException e) {
                        return false;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            int successCount = 0;
            int failureCount = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }

            // then
            assertThat(successCount).isEqualTo(1);
            assertThat(failureCount).isEqualTo(1);

            Order order = orderRepository.findById(orderId)
                    .orElseThrow();

            Inventory inventory = inventoryRepository
                    .findByProductId(productId)
                    .orElseThrow();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

            // 최초 10 → 주문 -3 → 취소 +3
            assertThat(inventory.getQuantity()).isEqualTo(10);

            // INITIAL + 주문 출고 + 주문 취소 입고
            assertThat(historyRepository
                    .findByInventoryIdOrderByIdDesc(inventory.getId()))
                    .hasSize(3);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // 테스트가 통과한다고 모든 데드락 가능성이 사라졌다는 의미는 아님
    // 두 주문이 충돌하는 대표적인 상황에서 정상 완료되는지를 검증
    // 다른 재고 변경 경로나 트랜잭션도 잠금 순서에 영향
    @Test
    void 상품_순서가_다른_동시_주문도_정상_처리된다()
            throws Exception {

        // given
        Long keyboardId = productService.registerProduct(
                "동시 주문 키보드",
                new BigDecimal("50000"),
                10
        );

        Long mouseId = productService.registerProduct(
                "동시 주문 마우스",
                new BigDecimal("30000"),
                10
        );

        long beforeOrderCount = orderRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<List<OrderCreateItem>> requests = List.of(
                List.of(
                        new OrderCreateItem(keyboardId, 3),
                        new OrderCreateItem(mouseId, 2)
                ),
                List.of(
                        new OrderCreateItem(mouseId, 4),
                        new OrderCreateItem(keyboardId, 2)
                )
        );

        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (List<OrderCreateItem> items : requests) {

                futures.add(executor.submit(() -> {

                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "동시 시작 대기 시간 초과"
                        );
                    }

                    return orderService.createOrder(items);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            start.countDown();

            // 두 주문 모두 정상 완료되어야 함
            Long firstOrderId = futures.get(0)
                    .get(60, TimeUnit.SECONDS);

            Long secondOrderId = futures.get(1)
                    .get(60, TimeUnit.SECONDS);

            // then
            assertThat(firstOrderId).isNotEqualTo(secondOrderId);

            assertThat(orderRepository.count())
                    .isEqualTo(beforeOrderCount + 2);

            assertThat(inventoryRepository
                    .findByProductId(keyboardId)
                    .orElseThrow()
                    .getQuantity())
                    .isEqualTo(5);

            assertThat(inventoryRepository
                    .findByProductId(mouseId)
                    .orElseThrow()
                    .getQuantity())
                    .isEqualTo(4);

            Order firstOrder = orderRepository.findById(firstOrderId)
                    .orElseThrow();

            Order secondOrder = orderRepository.findById(secondOrderId)
                    .orElseThrow();

            assertThat(firstOrder.getTotalAmount())
                    .isEqualByComparingTo("210000");

            assertThat(secondOrder.getTotalAmount())
                    .isEqualByComparingTo("220000");

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void 동일한_멱등성_키의_동시_주문은_한번만_생성된다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "멱등성 동시 주문 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        String idempotencyKey = UUID.randomUUID().toString();

        long beforeOrderCount = orderRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 동일한 주문 요청 2건이 하나의 멱등성 키를 놓고 경쟁
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 2; i++) {

                futures.add(executor.submit(() -> {

                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "동시 시작 대기 시간 초과"
                        );
                    }

                    return orderService.createOrder(
                            List.of(new OrderCreateItem(productId, 3)),
                            idempotencyKey
                    );
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // 두 스레드 동시 시작
            start.countDown();

            Long firstOrderId = futures.get(0)
                    .get(60, TimeUnit.SECONDS);

            Long secondOrderId = futures.get(1)
                    .get(60, TimeUnit.SECONDS);

            // 동일한 주문 ID를 반환해야 함
            assertThat(firstOrderId)
                    .isEqualTo(secondOrderId);

            // 실제 주문 생성은 1건
            assertThat(orderRepository.count())
                    .isEqualTo(beforeOrderCount + 1);

            Inventory inventory = inventoryRepository
                    .findByProductId(productId)
                    .orElseThrow();

            // 재고는 한 번만 차감
            assertThat(inventory.getQuantity())
                    .isEqualTo(7);

            // INITIAL 1건 + OUTBOUND 1건
            assertThat(historyRepository
                    .findByInventoryIdOrderByIdDesc(inventory.getId()))
                    .hasSize(2);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // 먼저 처리된 요청만 성공하고
    // 나중 요청은 IdempotencyConflictException이 발생해야 함
    @Test
    void 동일한_키로_서로_다른_주문을_동시에_요청하면_하나만_성공한다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "멱등성 충돌 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        String idempotencyKey = UUID.randomUUID().toString();

        long beforeOrderCount = orderRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // CountDownLatch로 동시에 시작하더라도
        // 어느 스레드가 먼저 락을 획득할지는 알 수 없음
        // 재고를 무조건 8개 또는 5개라고 검증하지 않고,
        // 실제 DB에 저장된 주문 수량을 조회해서 검증
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int quantity : List.of(2, 5)) {

                futures.add(executor.submit(() -> {

                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "동시 시작 대기 시간 초과"
                        );
                    }

                    try {
                        orderService.createOrder(
                                List.of(new OrderCreateItem(productId, quantity)),
                                idempotencyKey
                        );

                        return true;

                    } catch (IdempotencyConflictException e) {
                        return false;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            start.countDown();

            int successCount = 0;
            int conflictCount = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    successCount++;
                } else {
                    conflictCount++;
                }
            }

            // then: 주문 1건 성공, 다른 요청은 충돌
            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);

            assertThat(orderRepository.count())
                    .isEqualTo(beforeOrderCount + 1);

            // 실제 저장된 주문을 기준으로 재고 검증
            Order savedOrder = orderRepository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseThrow();

            assertThat(savedOrder.getItems()).hasSize(1);

            int savedQuantity = savedOrder.getItems()
                    .get(0)
                    .getQuantity();

            // 먼저 성공한 주문이 2개 또는 5개일 수 있음
            assertThat(savedQuantity).isIn(2, 5);

            Inventory inventory = inventoryRepository
                    .findByProductId(productId)
                    .orElseThrow();

            assertThat(inventory.getQuantity())
                    .isEqualTo(10 - savedQuantity);

            // INITIAL 1건 + OUTBOUND 1건
            assertThat(historyRepository
                    .findByInventoryIdOrderByIdDesc(inventory.getId()))
                    .hasSize(2);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}