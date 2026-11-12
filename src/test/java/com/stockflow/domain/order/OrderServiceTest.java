package com.stockflow.domain.order;

import com.stockflow.domain.inventory.*;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.order.outbox.OrderOutbox;
import com.stockflow.domain.order.outbox.OrderOutboxMessage;
import com.stockflow.domain.order.outbox.OrderOutboxRepository;
import com.stockflow.domain.order.outbox.OutboxStatus;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
// 실제 서비스 트랜잭션이 커밋하거나 롤백한 결과를 확인하기 위해서
// @Transactional X
class OrderServiceTest {

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

    @Autowired
    OrderOutboxRepository outboxRepository;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void 주문_생성시_재고가_차감된다() {
        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 3))
        );

        Order order = orderRepository.findById(orderId)
                .orElseThrow();

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CREATED);

        assertThat(order.getTotalAmount())
                .isEqualByComparingTo("150000");

        assertThat(inventory.getQuantity()).isEqualTo(7);

        assertThat(historyRepository
                .findByInventoryIdOrderByIdDesc(inventory.getId()))
                .hasSize(2);
    }

    @Test
    void 재고가_부족하면_주문이_생성되지_않는다() {
        Long productId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 2
        );

        long beforeCount = orderRepository.count();

        assertThatThrownBy(() ->
                orderService.createOrder(
                        List.of(new OrderCreateItem(productId, 3))
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount);

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        assertThat(inventory.getQuantity()).isEqualTo(2);
    }

    @Test
    void 여러_상품_주문중_실패하면_전체_롤백된다() {
        Long keyboardId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        Long mouseId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 1
        );

        assertThatThrownBy(() ->
                orderService.createOrder(List.of(
                        new OrderCreateItem(keyboardId, 3),
                        new OrderCreateItem(mouseId, 5)
                ))
        ).isInstanceOf(IllegalStateException.class);

        Inventory keyboardInventory = inventoryRepository
                .findByProductId(keyboardId)
                .orElseThrow();

        Inventory mouseInventory = inventoryRepository
                .findByProductId(mouseId)
                .orElseThrow();

        assertThat(keyboardInventory.getQuantity()).isEqualTo(10);
        assertThat(mouseInventory.getQuantity()).isEqualTo(1);

        assertThat(orderRepository.count()).isZero();

        assertThat(historyRepository
                .findByInventoryIdOrderByIdDesc(
                        keyboardInventory.getId()
                )).hasSize(1);
    }

    @Test
    void 주문을_취소하면_재고가_복구된다() {
        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 3))
        );

        orderService.cancelOrder(orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow();

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        assertThat(inventory.getQuantity()).isEqualTo(10);

        List<InventoryHistory> histories = historyRepository
                .findByInventoryIdOrderByIdDesc(inventory.getId());

        assertThat(histories).hasSize(3);

        assertThat(histories.get(0).getChangeType())
                .isEqualTo(InventoryChangeType.INBOUND);

        assertThat(histories.get(0).getQuantityChange())
                .isEqualTo(3);

        assertThat(histories.get(0).getQuantityAfter())
                .isEqualTo(10);
    }

    @Test
    void 이미_취소된_주문은_다시_취소할_수_없다() {
        Long productId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 3))
        );

        orderService.cancelOrder(orderId);

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        long beforeHistoryCount = historyRepository
                .findByInventoryIdOrderByIdDesc(inventory.getId())
                .size();

        assertThatThrownBy(() -> orderService.cancelOrder(orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("취소할 수 없는 주문 상태입니다.");

        Inventory found = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        assertThat(found.getQuantity()).isEqualTo(10);

        assertThat(historyRepository
                .findByInventoryIdOrderByIdDesc(found.getId()))
                .hasSize((int) beforeHistoryCount);
    }

    @Test
    void 여러_상품_주문을_취소하면_모든_재고가_복구된다() {
        Long keyboardId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        Long mouseId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 20
        );

        Long orderId = orderService.createOrder(List.of(
                new OrderCreateItem(keyboardId, 3),
                new OrderCreateItem(mouseId, 5)
        ));

        orderService.cancelOrder(orderId);

        assertThat(inventoryRepository.findByProductId(keyboardId)
                .orElseThrow().getQuantity()).isEqualTo(10);

        assertThat(inventoryRepository.findByProductId(mouseId)
                .orElseThrow().getQuantity()).isEqualTo(20);

        assertThat(orderRepository.findById(orderId)
                .orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 주문_생성시_Outbox_이벤트도_저장된다() {

        Long productId = productService.registerProduct(
                "Outbox 테스트 키보드",
                new BigDecimal("50000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 2))
        );

        List<OrderOutbox> events =
                outboxRepository.findByOrderIdOrderByIdAsc(orderId);

        assertThat(events).hasSize(1);

        OrderOutbox event = events.get(0);

        assertThat(event.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();

        OrderOutboxMessage message = jsonMapper.readValue(
                event.getPayload(),
                OrderOutboxMessage.class
        );

        assertThat(message.eventId()).isEqualTo(event.getEventId());
        assertThat(message.orderId()).isEqualTo(orderId);
        assertThat(message.eventType()).isEqualTo("ORDER_CREATED");
    }

    @Test
    // Outbox 저장 이전에 실패하는 시나리오
    void 주문_실패시_Outbox_이벤트도_생성되지_않는다() {

        Long productId = productService.registerProduct(
                "재고 부족 상품",
                new BigDecimal("30000"),
                1
        );

        long beforeCount = outboxRepository.count();

        assertThatThrownBy(() ->
                orderService.createOrder(
                        List.of(new OrderCreateItem(productId, 3))
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(outboxRepository.count())
                .isEqualTo(beforeCount);
    }

    @Test
    void 주문_취소시_Outbox에_취소_이벤트가_저장된다() {

        Long productId = productService.registerProduct(
                "취소 이벤트 상품",
                new BigDecimal("20000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 3))
        );

        orderService.cancelOrder(orderId);

        List<OrderOutbox> events =
                outboxRepository.findByOrderIdOrderByIdAsc(orderId);

        assertThat(events).hasSize(2);

        assertThat(events)
                .extracting(OrderOutbox::getEventType)
                .containsExactly(
                        "ORDER_CREATED",
                        "ORDER_CANCELLED"
                );

        assertThat(events)
                .extracting(OrderOutbox::getStatus)
                .containsOnly(OutboxStatus.PENDING);

        assertThat(orderRepository.findById(orderId)
                .orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    // 멱등성 로직과 Outbox가 충돌하지 않는지 확인
    void 동일한_멱등성_키로_재요청하면_Outbox도_중복_생성되지_않는다() {

        Long productId = productService.registerProduct(
                "멱등성 Outbox 상품",
                new BigDecimal("10000"),
                10
        );

        String key = UUID.randomUUID().toString();

        List<OrderCreateItem> items = List.of(
                new OrderCreateItem(productId, 2)
        );

        Long firstOrderId = orderService.createOrder(items, key);
        Long secondOrderId = orderService.createOrder(items, key);

        assertThat(secondOrderId).isEqualTo(firstOrderId);

        assertThat(outboxRepository
                .findByOrderIdOrderByIdAsc(firstOrderId))
                .hasSize(1);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity())
                .isEqualTo(8);
    }
}