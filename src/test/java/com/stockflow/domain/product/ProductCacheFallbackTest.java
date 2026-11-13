package com.stockflow.domain.product;

import com.stockflow.domain.product.dto.ProductInfoResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
// Redis 장애 테스트
@org.junit.jupiter.api.Tag("integration")
class ProductCacheFallbackTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ProductRepository productRepository;

    @Autowired
    ProductInfoCacheService cacheService;

    @MockitoBean
    StringRedisTemplate redisTemplate;

    @MockitoBean
    ValueOperations<String, String> valueOperations;

    @Test
    void Redis_조회가_실패해도_DB에서_상품정보를_반환한다() {

        Product product = productRepository.save(
                new Product("키보드", new BigDecimal("50000"))
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(anyString()))
                .thenThrow(new RedisConnectionFailureException(
                        "Redis 연결 실패"
                ));

        ProductInfoResponse response =
                cacheService.getProductInfo(product.getId());

        assertThat(response.name()).isEqualTo("키보드");
        assertThat(response.price())
                .isEqualByComparingTo("50000");
    }

    @Test
    void Redis_저장이_실패해도_DB조회_결과를_반환한다() {

        Product product = productRepository.save(
                new Product("마우스", new BigDecimal("30000"))
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(anyString()))
                .thenReturn(null);

        doThrow(new RedisConnectionFailureException(
                "Redis 저장 실패"
        )).when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        ProductInfoResponse response =
                cacheService.getProductInfo(product.getId());

        assertThat(response.name()).isEqualTo("마우스");
        assertThat(response.price())
                .isEqualByComparingTo("30000");

        verify(valueOperations).set(
                anyString(),
                anyString(),
                any(Duration.class)
        );
    }
}