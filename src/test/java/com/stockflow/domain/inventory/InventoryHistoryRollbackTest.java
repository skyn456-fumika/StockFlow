package com.stockflow.domain.inventory;

import com.stockflow.domain.product.ProductService;
import com.stockflow.support.SharedContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

// 재고 이력 저장 실패 시 롤백 테스트
@SpringBootTest
@Tag("integration")
class InventoryHistoryRollbackTest extends SharedContainers {

    @Autowired
    ProductService productService;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    InventoryHistoryRepository historyRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 이력_저장에_실패하면_재고_차감도_롤백된다() {

        // 1. 재고 10개인 상품 생성
        // given: 재고 10개인 상품 등록
        Long productId = productService.registerProduct(
                "롤백 테스트 상품",
                new BigDecimal("50000"),
                10
        );

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        Long inventoryId = inventory.getId();

        // 테스트용 제약조건:
        // OUTBOUND 이력 저장을 의도적으로 실패시킴
        jdbcTemplate.execute("""
            ALTER TABLE inventory_histories
            ADD CONSTRAINT chk_test_reject_outbound
            CHECK (change_type <> 'OUTBOUND')
            """);

        try {
            // 2. 재고 3개 출고 시도
            // when: 출고 처리 중 이력 INSERT 실패
            assertThatThrownBy(() ->
                    inventoryService.decrease(productId, 3)
            ).isInstanceOf(RuntimeException.class);
            // 3. 재고 변경 이력 INSERT에서 DB 오류 발생
            // 4. 전체 트랜잭션 롤백

            // then: 재고 차감도 롤백됐는지 확인
            Inventory found = inventoryRepository
                    .findByProductId(productId)
                    .orElseThrow();

            // 5. 재고가 여전히 10개인지 확인
            assertThat(found.getQuantity()).isEqualTo(10);

            // INITIAL 이력만 남아 있어야 함
            assertThat(
                    historyRepository
                            .findByInventoryIdOrderByIdDesc(inventoryId)
            ).hasSize(1);

        } finally {
            // 테스트용 제약조건 제거
            jdbcTemplate.execute("""
                ALTER TABLE inventory_histories
                DROP CONSTRAINT IF EXISTS chk_test_reject_outbound
                """);
        }
    }
}