package com.stockflow.domain.product;

import com.stockflow.domain.inventory.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
// API 통합 테스트
@org.junit.jupiter.api.Tag("integration")
class ProductControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    ProductService productService;

    @Test
    void 상품_등록_API는_201을_반환한다() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "name": "기계식 키보드",
                        "price": 89000,
                        "initialQuantity": 25
                    }
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").isNumber())
                .andExpect(header().exists("Location"));

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(inventoryRepository.count()).isEqualTo(1);
    }

    @Test
    void 음수_초기_재고는_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "name": "마우스",
                        "price": 30000,
                        "initialQuantity": -1
                    }
                    """))
                .andExpect(status().isBadRequest());

        assertThat(productRepository.count()).isZero();
        assertThat(inventoryRepository.count()).isZero();
    }

    @Test
    void 상품_상세_조회_API는_상품과_재고를_반환한다() throws Exception {

        Long productId = productService.registerProduct(
                "게이밍 마우스",
                new BigDecimal("59000"),
                15
        );

        mockMvc.perform(get("/api/products/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.name").value("게이밍 마우스"))
                .andExpect(jsonPath("$.price").value(59000))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.quantity").value(15));
    }

    @Test
    void 존재하지_않는_상품_조회는_404를_반환한다() throws Exception {

        mockMvc.perform(get("/api/products/{productId}", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void 상품_목록을_페이징하여_조회한다() throws Exception {
        productService.registerProduct(
                "키보드", new BigDecimal("75000"), 10
        );
        productService.registerProduct(
                "마우스", new BigDecimal("35000"), 20
        );
        productService.registerProduct(
                "모니터", new BigDecimal("250000"), 5
        );

        mockMvc.perform(get("/api/products")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("모니터"))
                .andExpect(jsonPath("$.content[0].quantity").value(5))
                .andExpect(jsonPath("$.content[1].name").value("마우스"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void 두번째_페이지에는_남은_상품이_조회된다() throws Exception {
        productService.registerProduct(
                "키보드", new BigDecimal("75000"), 10
        );
        productService.registerProduct(
                "마우스", new BigDecimal("35000"), 20
        );
        productService.registerProduct(
                "모니터", new BigDecimal("250000"), 5
        );

        mockMvc.perform(get("/api/products")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("키보드"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void 잘못된_상품_페이지_요청은_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("page", "-1")
                        .param("size", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_PAGINATION"));
    }
}