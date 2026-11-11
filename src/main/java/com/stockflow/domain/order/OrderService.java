package com.stockflow.domain.order;

import com.stockflow.domain.inventory.*;
import com.stockflow.domain.inventory.exception.InsufficientStockException;
import com.stockflow.domain.inventory.exception.InventoryNotFoundException;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.order.dto.OrderDetailResponse;
import com.stockflow.domain.order.exception.*;
import com.stockflow.domain.product.Product;
import com.stockflow.domain.product.ProductRepository;
import com.stockflow.domain.product.ProductStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository historyRepository;

    public OrderService(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            InventoryHistoryRepository historyRepository
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.historyRepository = historyRepository;
    }

    // 재고 차감, 변경 이력 저장, 주문 저장을 하나의 트랜잭션으로 묶음
    // 여러 상품 중 마지막 상품의 재고가 부족하더라도 앞서 차감한 재고와 이력이 모두 롤백
    @Transactional
    public Long createOrder(List<OrderCreateItem> requestItems) {

        if (requestItems == null || requestItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "주문 상품은 1개 이상이어야 합니다."
            );
        }

        // 상품 ID 중복 및 수량 검증
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

        // 여러 상품 주문 시 락 획득 순서를 일정하게 유지
        List<OrderCreateItem> sortedItems = requestItems.stream()
                // 상품 ID 정렬
                .sorted(Comparator.comparing(OrderCreateItem::productId))
                .toList();

        Order order = new Order();

        for (OrderCreateItem item : sortedItems) {

            Product product = productRepository
                    .findById(item.productId())
                    .orElseThrow(() ->
                            new OrderProductNotFoundException(item.productId())
                    );

            if (product.getStatus() != ProductStatus.ACTIVE) {
                throw new ProductNotAvailableException(product.getId());
            }

            // 동일 재고를 동시에 변경하는 요청은 행 잠금을 통해 순차 처리되도록
            // 비관적 락
            Inventory inventory = inventoryRepository
                    .findByProductIdForUpdate(product.getId())
                    .orElseThrow(() ->
                            new InventoryNotFoundException(product.getId())
                    );

            // 재고 부족 시 예외 발생
            inventory.decrease(item.quantity());

            // 주문으로 인한 재고 변경 이력
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

        return saved.getId();
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

        order.cancel();
    }
}