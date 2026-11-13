package com.stockflow.domain.product;

import com.stockflow.domain.product.dto.ProductInfoResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Tag("integration")
class ProductInfoCacheServiceTest {

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
    ProductInfoCacheService cacheService;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 첫_조회시_DB에서_읽고_Redis에_캐싱한다() {

        Product product = productRepository.save(
                new Product("키보드", new BigDecimal("50000"))
        );

        Long productId = product.getId();
        String key = "stockflow:product:info:" + productId;

        try {
            ProductInfoResponse response =
                    cacheService.getProductInfo(productId);

            assertThat(response.name()).isEqualTo("키보드");
            assertThat(response.price())
                    .isEqualByComparingTo("50000");

            String cached = redisTemplate.opsForValue().get(key);

            assertThat(cached).isNotNull();
            assertThat(cached).contains("키보드");

            Long ttl = redisTemplate.getExpire(key);

            assertThat(ttl).isPositive();
            assertThat(ttl).isLessThanOrEqualTo(600L);

        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void 두번째_조회는_DB가_변경되어도_캐시를_사용한다() {

        Product product = productRepository.save(
                new Product("마우스", new BigDecimal("30000"))
        );

        Long productId = product.getId();
        String key = "stockflow:product:info:" + productId;

        try {
            // 최초 조회 → Redis 캐시 저장
            ProductInfoResponse first =
                    cacheService.getProductInfo(productId);

            // DB의 상품명만 직접 수정
            jdbcTemplate.update(
                    "UPDATE products SET name = ? WHERE id = ?",
                    "변경된 마우스",
                    productId
            );

            // 다시 조회 → Redis 값을 사용해야 함
            ProductInfoResponse second =
                    cacheService.getProductInfo(productId);

            assertThat(first.name()).isEqualTo("마우스");
            assertThat(second.name()).isEqualTo("마우스");

            // Redis 캐시를 삭제한 후 조회
            redisTemplate.delete(key);

            ProductInfoResponse third =
                    cacheService.getProductInfo(productId);

            assertThat(third.name())
                    .isEqualTo("변경된 마우스");

        } finally {
            redisTemplate.delete(key);
        }
    }
}