package com.stockflow.domain.order;

import com.stockflow.domain.inventory.*;
import com.stockflow.domain.inventory.exception.InsufficientStockException;
import com.stockflow.domain.inventory.exception.InventoryNotFoundException;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.order.dto.OrderDetailResponse;
import com.stockflow.domain.order.exception.*;
import com.stockflow.domain.order.outbox.OrderOutbox;
import com.stockflow.domain.order.outbox.OrderOutboxMessage;
import com.stockflow.domain.order.outbox.OrderOutboxRepository;
import com.stockflow.domain.product.Product;
import com.stockflow.domain.product.ProductRepository;
import com.stockflow.domain.product.ProductStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository historyRepository;
    private final OrderIdempotencyLock idempotencyLock;
    private final OrderOutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    public OrderService(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            InventoryHistoryRepository historyRepository,
            OrderIdempotencyLock idempotencyLock,
            OrderOutboxRepository outboxRepository,
            JsonMapper jsonMapper
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.historyRepository = historyRepository;
        this.idempotencyLock = idempotencyLock;
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public Long createOrder(List<OrderCreateItem> requestItems) {
        return createOrder(requestItems, null);
    }

    // 재고 차감, 변경 이력 저장, 주문 저장을 하나의 트랜잭션으로 묶음
    // 여러 상품 중 마지막 상품의 재고가 부족하더라도 앞서 차감한 재고와 이력이 모두 롤백
    @Transactional
    public Long createOrder(
            List<OrderCreateItem> requestItems,
            String idempotencyKey
    ) {

        if (requestItems == null || requestItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "주문 상품은 1개 이상이어야 합니다."
            );
        }

        Set<Long> productIds = new HashSet<>();

        for (OrderCreateItem item : requestItems) {
            if (item == null
                    || item.productId() == null
                    || item.quantity() <= 0) {
                throw new IllegalArgumentException(
                        "상품 ID와 올바른 주문 수량이 필요합니다."
                );
            }

            if (!productIds.add(item.productId())) {
                throw new DuplicateOrderProductException(item.productId());
            }
        }

        // 멱등성 키가 있는 요청만 기존 주문 확인
        if (idempotencyKey != null) {

            if (idempotencyKey.isBlank()
                    || idempotencyKey.length() > 100) {
                throw new IllegalArgumentException(
                        "올바른 멱등성 키가 필요합니다."
                );
            }

            // 동일한 멱등성 키에 대한 동시 요청 직렬화
            // 이 메서드에는 반드시 @Transactional이 있어야 함
            // 잠금 획득부터 주문 저장과 커밋까지 같은 트랜잭션 안에서 실행되어야 하기 때문
            idempotencyLock.lock(idempotencyKey);

            var existingOrder = orderRepository
                    .findByIdempotencyKey(idempotencyKey);

            if (existingOrder.isPresent()) {
                Order existing = existingOrder.get();

                if (!hasSameItems(existing, requestItems)) {
                    throw new IdempotencyConflictException();
                }

                return existing.getId();
            }
        }

        List<OrderCreateItem> sortedItems = requestItems.stream()
                .sorted(Comparator.comparing(OrderCreateItem::productId))
                .toList();

        Order order = idempotencyKey == null
                ? new Order()
                : new Order(idempotencyKey);

        for (OrderCreateItem item : sortedItems) {

            Product product = productRepository
                    .findById(item.productId())
                    .orElseThrow(() ->
                            new OrderProductNotFoundException(item.productId())
                    );

            if (product.getStatus() != ProductStatus.ACTIVE) {
                throw new ProductNotAvailableException(product.getId());
            }

            Inventory inventory = inventoryRepository
                    .findByProductIdForUpdate(product.getId())
                    .orElseThrow(() ->
                            new InventoryNotFoundException(product.getId())
                    );

            inventory.decrease(item.quantity());

            historyRepository.save(
                    new InventoryHistory(
                            inventory,
                            InventoryChangeType.OUTBOUND,
                            -item.quantity(),
                            inventory.getQuantity()
                    )
            );

            order.addItem(new OrderItem(
                    product,
                    item.quantity()
            ));
        }

        Order saved = orderRepository.save(order);

        saveOutboxEvent(saved.getId(), "ORDER_CREATED");

        return saved.getId();
    }

    // 비교 메서드
    // 상품 순서와 관계없이 상품 ID와 수량을 검증
    private boolean hasSameItems(
            Order order,
            List<OrderCreateItem> requestItems
    ) {
        if (order.getItems().size() != requestItems.size()) {
            return false;
        }

        return requestItems.stream().allMatch(request ->
                order.getItems().stream().anyMatch(saved ->
                        saved.getProduct().getId()
                                .equals(request.productId())
                                && saved.getQuantity() == request.quantity()
                )
        );
    }

    // 상세 조회 메서드
    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long orderId) {

        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 트랜잭션 내부에서 DTO로 변환
        // JPA의 연관 객체를 조회하는 과정에서
        // 영속성 컨텍스트가 필요할 수 있기 때문에
        // 서비스 내부에서 응답 DTO를 만들어 반환하는 구조로
        return OrderDetailResponse.from(order);
    }

    // 주문 취소 메서드
    @Transactional
    public void cancelOrder(Long orderId) {

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.CREATED) {
            throw new OrderCancellationException();
        }

        // 여러 재고 행의 잠금 순서를 일정하게 유지
        List<OrderItem> sortedItems = order.getItems().stream()
                .sorted(Comparator.comparing(
                        item -> item.getProduct().getId()
                ))
                .toList();

        for (OrderItem item : sortedItems) {

            Long productId = item.getProduct().getId();

            Inventory inventory = inventoryRepository
                    .findByProductIdForUpdate(productId)
                    .orElseThrow(() ->
                            new InventoryNotFoundException(productId)
                    );

            inventory.increase(item.getQuantity());

            historyRepository.save(
                    new InventoryHistory(
                            inventory,
                            InventoryChangeType.INBOUND,
                            item.getQuantity(),
                            inventory.getQuantity()
                    )
            );
        }

        // 주문 취소 상태 변경과 이벤트 저장이 같은 트랜잭션에서 처리
        order.cancel();

        saveOutboxEvent(orderId, "ORDER_CANCELLED");
    }

    // Outbox 저장용 내부 메서드
    private void saveOutboxEvent(
            Long orderId,
            String eventType
    ) {
        UUID eventId = UUID.randomUUID();

        OrderOutboxMessage message = new OrderOutboxMessage(
                eventId,
                orderId,
                eventType,
                LocalDateTime.now()
        );

        String payload = jsonMapper.writeValueAsString(message);

        outboxRepository.save(
                new OrderOutbox(
                        eventId,
                        orderId,
                        eventType,
                        payload
                )
        );
    }
}