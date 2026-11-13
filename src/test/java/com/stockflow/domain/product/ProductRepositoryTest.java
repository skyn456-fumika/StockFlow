package com.stockflow.domain.product;

import com.stockflow.support.SharedContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Tag("integration")
class ProductRepositoryTest extends SharedContainers {

    @Autowired
    ProductRepository productRepository;

    @Test
    void 상품을_저장하고_조회한다() {
        // given
        Product product = new Product(
                "테스트 상품",
                new BigDecimal("15000.00")
        );

        // when
        Product saved = productRepository.save(product);

        // then
        Product found = productRepository.findById(saved.getId())
                .orElseThrow();

        assertThat(found.getName()).isEqualTo("테스트 상품");
        assertThat(found.getPrice())
                .isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(found.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(found.getCreatedAt()).isNotNull();
    }
}