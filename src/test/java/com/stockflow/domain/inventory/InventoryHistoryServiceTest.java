package com.stockflow.domain.inventory;

import com.stockflow.domain.product.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

// 각 서비스 메서드가 실제로 커밋한 결과를
// 새로운 Repository 조회로 확인하기 위해서
// @Transactional X
@SpringBootTest
@Testcontainers
class InventoryHistoryServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ProductService productService;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    InventoryHistoryRepository historyRepository;

    private List<InventoryHistory> getHistories(Long productId) {
        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        return historyRepository
                .findByInventoryIdOrderByIdDesc(inventory.getId());
    }

    @Test
    void 상품_등록시_초기_재고_이력이_생성된다() {
        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        List<InventoryHistory> histories = getHistories(productId);

        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getChangeType())
                .isEqualTo(InventoryChangeType.INITIAL);
        assertThat(histories.get(0).getQuantityChange()).isEqualTo(10);
        assertThat(histories.get(0).getQuantityAfter()).isEqualTo(10);
    }

    @Test
    void 입고와_출고_이력이_정상적으로_기록된다() {
        Long productId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 10
        );

        inventoryService.increase(productId, 5);
        inventoryService.decrease(productId, 3);

        List<InventoryHistory> histories = getHistories(productId);

        assertThat(histories).hasSize(3);

        assertThat(histories.get(0).getChangeType())
                .isEqualTo(InventoryChangeType.OUTBOUND);
        assertThat(histories.get(0).getQuantityChange()).isEqualTo(-3);
        assertThat(histories.get(0).getQuantityAfter()).isEqualTo(12);

        assertThat(histories.get(1).getChangeType())
                .isEqualTo(InventoryChangeType.INBOUND);
        assertThat(histories.get(1).getQuantityChange()).isEqualTo(5);
        assertThat(histories.get(1).getQuantityAfter()).isEqualTo(15);
    }

    @Test
    void 재고_부족으로_출고_실패시_이력이_추가되지_않는다() {
        Long productId = productService.registerProduct(
                "모니터", new BigDecimal("200000"), 2
        );

        assertThatThrownBy(() ->
                inventoryService.decrease(productId, 5)
        ).isInstanceOf(IllegalStateException.class);

        assertThat(getHistories(productId)).hasSize(1);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(2);
    }
}