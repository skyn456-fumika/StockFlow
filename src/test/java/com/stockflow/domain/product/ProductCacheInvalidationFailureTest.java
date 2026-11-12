package com.stockflow.domain.product;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
class ProductCacheInvalidationFailureTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ProductService productService;

    @Autowired
    ProductRepository productRepository;

    @MockitoBean
    StringRedisTemplate redisTemplate;

    @Test
    void Redis_캐시_삭제가_실패해도_상품정보_수정은_성공한다() {

        // given
        // 1. 상품 등록
        Long productId = productService.registerProduct(
                "일반 키보드",
                new BigDecimal("50000"),
                10
        );

        // Redis 삭제 요청 시 연결 오류 발생
        // 2. Redis 캐시 삭제 실패 상황 설정
        when(redisTemplate.delete(anyString()))
                .thenThrow(
                        new RedisConnectionFailureException(
                                "Redis 연결 실패"
                        )
                );

        // when & then: 상품 수정에서는 예외가 발생하지 않아야 함
        // 3. 상품명 및 가격 수정
        assertThatCode(() ->
                productService.updateProductInfo(
                        productId,
                        "기계식 키보드",
                        new BigDecimal("70000")
                )
        ).doesNotThrowAnyException();

        // 실제 PostgreSQL에 변경 내용이 저장되어야 함
        // 4. 서비스에서 예외가 발생하지 않는지 확인
        Product product = productRepository.findById(productId)
                .orElseThrow();

        // 5. PostgreSQL에 변경된 값이 저장되었는지 확인
        assertThat(product.getName())
                .isEqualTo("기계식 키보드");

        assertThat(product.getPrice())
                .isEqualByComparingTo("70000");

        // 캐시 삭제를 실제로 시도했는지 검증
        // 6. Redis 삭제 시도 자체는 이루어졌는지 확인
        verify(redisTemplate).delete(
                "stockflow:product:info:" + productId
        );
    }
}