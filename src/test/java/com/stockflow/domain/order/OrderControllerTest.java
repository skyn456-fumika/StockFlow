package com.stockflow.domain.order;

import com.stockflow.domain.inventory.InventoryRepository;
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

import org.springframework.jdbc.core.JdbcTemplate;

import com.stockflow.domain.order.dto.OrderCreateItem;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
// 실제 서비스 트랜잭션이 커밋하거나 롤백한 결과를 검증해야 하므로
// @Transactional X
class OrderControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProductService productService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 주문_생성_API는_201을_반환한다() throws Exception {

        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        long beforeCount = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "items": [
                        {
                            "productId": %d,
                            "quantity": 3
                        }
                    ]
                }
                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNumber())
                .andExpect(header().exists("Location"));

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount + 1);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(7);
    }

    @Test
    void 주문_상품이_비어있으면_400을_반환한다() throws Exception {
        long beforeCount = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "items": []
                    }
                    """))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount);
    }

    @Test
    void 주문_수량이_0이면_400을_반환한다() throws Exception {
        Long productId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 10
        );

        long beforeCount = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "items": [
                            {
                                "productId": %d,
                                "quantity": 0
                            }
                        ]
                    }
                    """.formatted(productId)))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(10);
    }

    @Test
    void 주문_도중_재고가_부족하면_409를_반환한다() throws Exception {
        Long productId = productService.registerProduct(
                "모니터", new BigDecimal("200000"), 2
        );

        long beforeCount = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "items": [
                            {
                                "productId": %d,
                                "quantity": 3
                            }
                        ]
                    }
                    """.formatted(productId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("INSUFFICIENT_STOCK"));

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(2);
    }

    @Test
    void 존재하지_않는_상품_주문은_404를_반환한다() throws Exception {
        long beforeCount = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "items": [
                        {"productId": 999999, "quantity": 1}
                    ]
                }
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("ORDER_PRODUCT_NOT_FOUND"));

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount);
    }

    @Test
    void 동일_상품을_중복_주문하면_400을_반환한다() throws Exception {
        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        long beforeCount = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "items": [
                        {"productId": %d, "quantity": 2},
                        {"productId": %d, "quantity": 3}
                    ]
                }
                """.formatted(productId, productId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("DUPLICATE_ORDER_PRODUCT"));

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(10);
    }

    // 현재 Product 엔티티에는 판매 상태를 변경하는 메서드가 없으므로,
    // 테스트에서는 JdbcTemplate을 사용해 테스트 DB의 상품 상태를 직접 변경
    @Test
    void 판매_중지_상품_주문은_409를_반환한다() throws Exception {
        Long productId = productService.registerProduct(
                "판매 중지 상품", new BigDecimal("30000"), 10
        );

        jdbcTemplate.update(
                "UPDATE products SET status = 'INACTIVE' WHERE id = ?",
                productId
        );

        long beforeCount = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {
                    "items": [
                        {"productId": %d, "quantity": 2}
                    ]
                }
                """.formatted(productId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("PRODUCT_NOT_AVAILABLE"));

        assertThat(orderRepository.count())
                .isEqualTo(beforeCount);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(10);
    }

    // OrderService에서 상품 ID순으로 정렬하고 있으므로
    // 테스트에서도 키보드와 마우스가 ID순으로 반환
    @Test
    void 주문_상세_조회_API는_주문과_상품목록을_반환한다() throws Exception {

        Long keyboardId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        Long mouseId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 10
        );

        Long orderId = orderService.createOrder(List.of(
                new OrderCreateItem(keyboardId, 2),
                new OrderCreateItem(mouseId, 3)
        ));

        mockMvc.perform(get("/api/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(190000))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productName").value("키보드"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").value(100000))
                .andExpect(jsonPath("$.items[1].productName").value("마우스"))
                .andExpect(jsonPath("$.items[1].quantity").value(3))
                .andExpect(jsonPath("$.items[1].subtotal").value(90000));
    }

    @Test
    void 존재하지_않는_주문_조회는_404를_반환한다() throws Exception {

        mockMvc.perform(get("/api/orders/{orderId}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 주문_취소_API는_204를_반환하고_재고를_복구한다() throws Exception {
        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 3))
        );

        mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId))
                .andExpect(status().isNoContent());

        assertThat(orderRepository.findById(orderId)
                .orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity())
                .isEqualTo(10);
    }

    @Test
    void 이미_취소된_주문은_409를_반환한다() throws Exception {
        Long productId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 3))
        );

        mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("ORDER_CANNOT_CANCEL"));

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity())
                .isEqualTo(10);
    }

    @Test
    void 존재하지_않는_주문_취소는_404를_반환한다() throws Exception {
        mockMvc.perform(post("/api/orders/{orderId}/cancel", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("ORDER_NOT_FOUND"));
    }
}