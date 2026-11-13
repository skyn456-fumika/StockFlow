package com.stockflow.domain.order.outbox;

import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import com.stockflow.support.SharedContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false",
        "stockflow.order.consumer.enabled=false"
})
// PostgreSQL 장애 감지 통합 테스트
@Tag("integration")
class OrderOutboxAlertIntegrationTest extends SharedContainers {

    @Autowired
    ProductService productService;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderOutboxMonitoringService monitoringService;

    @Autowired
    OrderOutboxRepository outboxRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long createPendingOutbox(String name) {

        Long productId = productService.registerProduct(
                name,
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        return outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0)
                .getId();
    }

    @Test
    void 실제_DB에서_장기대기와_반복실패_이벤트를_감지한다() {

        // 기존 데이터가 있어도 테스트할 수 있도록 최초 집계 저장
        OutboxAlertSummary before =
                monitoringService.getAlertSummary();

        // A: 정상 PENDING
        createPendingOutbox("장애 감지 정상 상품");

        // B: 20분 이상 대기한 PENDING
        Long staleId = createPendingOutbox(
                "장애 감지 장기 대기 상품"
        );

        jdbcTemplate.update(
                """
                UPDATE order_outbox
                SET created_at = ?
                WHERE id = ?
                """,
                LocalDateTime.now().minusMinutes(20),
                staleId
        );

        // C: 재시도 5회인 PENDING
        Long retryId = createPendingOutbox(
                "장애 감지 반복 실패 상품"
        );

        jdbcTemplate.update(
                """
                UPDATE order_outbox
                SET retry_count = 5
                WHERE id = ?
                """,
                retryId
        );

        // D: 장기 대기 및 반복 실패 조건에 해당하지만
        // 이미 PUBLISHED이므로 감지 대상에서 제외
        Long publishedId = createPendingOutbox(
                "장애 감지 발행 완료 상품"
        );

        jdbcTemplate.update(
                """
                UPDATE order_outbox
                SET status = 'PUBLISHED',
                    published_at = CURRENT_TIMESTAMP,
                    created_at = ?,
                    retry_count = 5
                WHERE id = ?
                """,
                LocalDateTime.now().minusMinutes(20),
                publishedId
        );

        // when
        OutboxAlertSummary after =
                monitoringService.getAlertSummary();

        // then
        assertThat(after.stalePending())
                .isEqualTo(before.stalePending() + 1);

        assertThat(after.highRetry())
                .isEqualTo(before.highRetry() + 1);

        assertThat(after.alert()).isTrue();
    }

    @Test
    // 하나의 이벤트가 장기 대기와
    // 반복 실패 조건에 모두 해당할 때 두 지표에 각각 포함되는지 확인
    void 장기대기와_반복실패에_모두_해당하면_각_지표에_집계된다() {

        OutboxAlertSummary before =
                monitoringService.getAlertSummary();

        Long outboxId = createPendingOutbox(
                "장애 감지 중복 조건 상품"
        );

        jdbcTemplate.update(
                """
                UPDATE order_outbox
                SET created_at = ?,
                    retry_count = 5
                WHERE id = ?
                """,
                LocalDateTime.now().minusMinutes(20),
                outboxId
        );

        OutboxAlertSummary after =
                monitoringService.getAlertSummary();

        assertThat(after.stalePending())
                .isEqualTo(before.stalePending() + 1);

        assertThat(after.highRetry())
                .isEqualTo(before.highRetry() + 1);

        assertThat(after.alert()).isTrue();
    }
}