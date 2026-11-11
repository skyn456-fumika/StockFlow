package com.stockflow.domain.order;

import com.stockflow.domain.inventory.Inventory;
import com.stockflow.domain.inventory.InventoryHistoryRepository;
import com.stockflow.domain.inventory.InventoryRepository;
import com.stockflow.domain.inventory.exception.InsufficientStockException;
import com.stockflow.domain.order.dto.OrderCreateItem;
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
}