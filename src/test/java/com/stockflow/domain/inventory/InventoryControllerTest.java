package com.stockflow.domain.inventory;

import com.stockflow.domain.product.ProductService;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.junit.jupiter.api.Tag("integration")
class InventoryControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProductService productService;

    @Autowired
    InventoryRepository inventoryRepository;

    @Test
    void 재고_입고_API는_수량을_증가시킨다() throws Exception {
        Long productId = productService.registerProduct(
                "키보드",
                new BigDecimal("50000"),
                10
        );

        mockMvc.perform(post("/api/inventories/{productId}/increase", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "amount": 5
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.quantity").value(15));

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(15);
    }

    @Test
    void 재고_출고_API는_수량을_감소시킨다() throws Exception {
        Long productId = productService.registerProduct(
                "마우스",
                new BigDecimal("30000"),
                10
        );

        mockMvc.perform(post("/api/inventories/{productId}/decrease", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "amount": 3
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(7));

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(7);
    }

    @Test
    void 입고_수량이_0이면_400을_반환한다() throws Exception {
        Long productId = productService.registerProduct(
                "모니터",
                new BigDecimal("200000"),
                10
        );

        mockMvc.perform(post("/api/inventories/{productId}/increase", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "amount": 0
                    }
                    """))
                .andExpect(status().isBadRequest());

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(10);
    }

    @Test
    void 재고가_부족하면_409를_반환한다() throws Exception {
        Long productId = productService.registerProduct(
                "헤드셋",
                new BigDecimal("50000"),
                2
        );

        mockMvc.perform(post("/api/inventories/{productId}/decrease", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "amount": 3
                }
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("재고가 부족합니다."));

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(2);
    }

    @Test
    void 존재하지_않는_재고는_404를_반환한다() throws Exception {
        mockMvc.perform(post("/api/inventories/{productId}/increase", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "amount": 5
                }
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void 재고_변경_이력을_최신순으로_조회한다() throws Exception {
        Long productId = productService.registerProduct(
                "이력 테스트 상품",
                new BigDecimal("50000"),
                10
        );

        mockMvc.perform(post("/api/inventories/{productId}/increase", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"amount": 5}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/inventories/{productId}/decrease", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"amount": 3}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/inventories/{productId}/histories", productId)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].changeType")
                        .value("OUTBOUND"))
                .andExpect(jsonPath("$.content[0].quantityChange")
                        .value(-3))
                .andExpect(jsonPath("$.content[0].quantityAfter")
                        .value(12))
                .andExpect(jsonPath("$.content[1].changeType")
                        .value("INBOUND"))
                .andExpect(jsonPath("$.content[1].quantityChange")
                        .value(5))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/inventories/{productId}/histories", productId)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].changeType")
                        .value("INITIAL"))
                .andExpect(jsonPath("$.content[0].quantityAfter")
                        .value(10));
    }

    @Test
    void 존재하지_않는_재고_이력_조회는_404를_반환한다() throws Exception {
        mockMvc.perform(get(
                        "/api/inventories/{productId}/histories",
                        999999L
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void 잘못된_재고_이력_페이지_요청은_400을_반환한다() throws Exception {
        mockMvc.perform(get(
                        "/api/inventories/{productId}/histories",
                        1L
                )
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_PAGINATION"));
    }
}