package com.stockflow.domain.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProductCacheControllerTest {

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
    MockMvc mockMvc;

    @Autowired
    ProductService productService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void 상품_조회_API는_Redis에_상품정보를_캐싱한다()
            throws Exception {

        Long productId = productService.registerProduct(
                "캐시 테스트 키보드",
                new BigDecimal("50000"),
                10
        );

        String key = "stockflow:product:info:" + productId;

        try {
            redisTemplate.delete(key);

            mockMvc.perform(get("/api/products/{productId}", productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name")
                            .value("캐시 테스트 키보드"));

            String cached = redisTemplate.opsForValue().get(key);

            assertThat(cached).isNotNull();
            assertThat(cached).contains("캐시 테스트 키보드");

        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void 상품_수정_API는_캐시를_삭제하고_변경된_정보를_반환한다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "일반 키보드",
                new BigDecimal("50000"),
                10
        );

        String key = "stockflow:product:info:" + productId;

        try {
            // 최초 상품 조회 → Redis 캐시 생성
            mockMvc.perform(get("/api/products/{productId}", productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("일반 키보드"));

            assertThat(redisTemplate.hasKey(key)).isTrue();

            // when: 상품 정보 변경
            mockMvc.perform(put("/api/products/{productId}", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                    {
                        "name": "기계식 키보드",
                        "price": 70000
                    }
                    """))
                    .andExpect(status().isNoContent());

            // DB 커밋 이후 캐시 삭제 확인
            assertThat(redisTemplate.hasKey(key)).isFalse();

            // then: 재조회 시 변경된 상품 정보 반환
            mockMvc.perform(get("/api/products/{productId}", productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("기계식 키보드"))
                    .andExpect(jsonPath("$.price").value(70000))
                    .andExpect(jsonPath("$.quantity").value(10));

            // 새로운 정보가 다시 캐싱되었는지 확인
            String cached = redisTemplate.opsForValue().get(key);

            assertThat(cached).isNotNull();
            assertThat(cached).contains("기계식 키보드");

        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void 상품_수정시_가격이_음수이면_400을_반환한다()
            throws Exception {

        Long productId = productService.registerProduct(
                "마우스",
                new BigDecimal("30000"),
                10
        );

        mockMvc.perform(put("/api/products/{productId}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "name": "수정된 마우스",
                    "price": -1000
                }
                """))
                .andExpect(status().isBadRequest());

        // 기존 정보가 유지되어야 함
        mockMvc.perform(get("/api/products/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("마우스"))
                .andExpect(jsonPath("$.price").value(30000));
    }

    @Test
    void 존재하지_않는_상품을_수정하면_404를_반환한다()
            throws Exception {

        mockMvc.perform(put("/api/products/{productId}", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "name": "없는 상품",
                    "price": 10000
                }
                """))
                .andExpect(status().isNotFound());
    }
}