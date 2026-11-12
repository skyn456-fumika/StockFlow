package com.stockflow.domain.product;

import com.stockflow.domain.product.dto.ProductInfoResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ProductCacheInvalidationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @Autowired
    ProductService productService;

    @Autowired
    ProductInfoCacheService cacheService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void 상품_정보_변경이_커밋되면_캐시가_삭제된다() {

        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        String key = "stockflow:product:info:" + productId;

        try {
            // 최초 조회로 캐시 생성
            cacheService.getProductInfo(productId);

            assertThat(redisTemplate.hasKey(key)).isTrue();

            // 상품 정보 변경 및 커밋
            productService.updateProductInfo(
                    productId,
                    "기계식 키보드",
                    new BigDecimal("70000")
            );

            // 커밋 이후 캐시가 삭제되어야 함
            assertThat(redisTemplate.hasKey(key)).isFalse();

            // 재조회 시 변경된 DB 정보로 캐시 생성
            ProductInfoResponse response =
                    cacheService.getProductInfo(productId);

            assertThat(response.name())
                    .isEqualTo("기계식 키보드");

            assertThat(response.price())
                    .isEqualByComparingTo("70000");

            assertThat(redisTemplate.hasKey(key)).isTrue();

        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void 상품_정보_변경이_롤백되면_캐시를_유지한다() {

        Long productId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 10
        );

        String key = "stockflow:product:info:" + productId;

        try {
            cacheService.getProductInfo(productId);

            assertThat(redisTemplate.hasKey(key)).isTrue();

            // 외부 트랜잭션에서 변경한 뒤 롤백
            transactionTemplate.executeWithoutResult(status -> {

                // 트랜잭션에 참여
                productService.updateProductInfo(
                        productId,
                        "변경된 마우스",
                        new BigDecimal("45000")
                );

                // 전체 변경이 롤백
                status.setRollbackOnly();
            });

            // 롤백되었으므로 캐시가 남아 있어야 함
            assertThat(redisTemplate.hasKey(key)).isTrue();

            ProductInfoResponse response =
                    cacheService.getProductInfo(productId);

            assertThat(response.name()).isEqualTo("마우스");

            assertThat(response.price())
                    .isEqualByComparingTo("30000");

        } finally {
            redisTemplate.delete(key);
        }
    }
}