package com.stockflow.domain.product;

import com.stockflow.domain.inventory.Inventory;
import com.stockflow.domain.inventory.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ProductTransactionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ProductRepository productRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void 재고_저장_실패시_상품_저장도_롤백된다() {
        long beforeCount = productRepository.count();

        assertThatThrownBy(() ->
                // 1. 트랜잭션 시작
                transactionTemplate.executeWithoutResult(status -> {
                    // 2. Product 저장
                    Product product = productRepository.save(
                            new Product(
                                    "롤백 테스트 상품",
                                    new BigDecimal("10000")
                            )
                    );

                    // 3. Inventory 첫 번째 저장
                    inventoryRepository.saveAndFlush(
                            new Inventory(product, 10)
                    );

                    // 4. 같은 Product로 두 번째 Inventory 저장 시도
                    // 동일 상품의 두 번째 재고 생성
                    // product_id UNIQUE 제약조건 위반
                    inventoryRepository.saveAndFlush(
                            new Inventory(product, 20)
                    );
                    // 5. UNIQUE(product_id) 위반 발생
                })
        ).isInstanceOf(RuntimeException.class);
        // 6. 트랜잭션 전체 롤백
        
        // 7. Product 개수가 테스트 전과 동일한지 검증
        assertThat(productRepository.count())
                .isEqualTo(beforeCount);
    }
}