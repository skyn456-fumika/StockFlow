package com.stockflow.domain.order.outbox;

import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
@Testcontainers
class OrderOutboxAttentionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    ProductService productService;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderOutboxRepository outboxRepository;

    @Autowired
    OrderOutboxMonitoringService monitoringService;

    @Autowired
    TransactionTemplate transactionTemplate;

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
    void 재시도_이력이나_발행_지연이_있는_PENDING만_조회한다() {

        // A: 최근 생성된 정상 PENDING 이벤트
        Long readyId = createPendingOutbox(
                "모니터링 정상 대기 상품"
        );

        // B: 재시도 이력이 있는 PENDING 이벤트
        Long retryId = createPendingOutbox(
                "모니터링 재시도 상품"
        );

        transactionTemplate.executeWithoutResult(status -> {
            OrderOutbox retry = outboxRepository
                    .findByIdForUpdate(retryId)
                    .orElseThrow();

            retry.scheduleRetry(LocalDateTime.now());
        });

        // C: 생성한 지 10분이 지난 PENDING 이벤트
        Long oldId = createPendingOutbox(
                "모니터링 장기 대기 상품"
        );

        // createdAt 수정 메서드를 엔티티에 추가하지 않고,
        // 통합 테스트에서만 SQL로 생성 시각 변경
        jdbcTemplate.update(
                """
                UPDATE order_outbox
                SET created_at = ?
                WHERE id = ?
                """,
                LocalDateTime.now().minusMinutes(10),
                oldId
        );

        // D: 오래됐지만 이미 PUBLISHED 상태인 이벤트
        Long publishedId = createPendingOutbox(
                "모니터링 발행 완료 상품"
        );

        transactionTemplate.executeWithoutResult(status -> {
            OrderOutbox published = outboxRepository
                    .findByIdForUpdate(publishedId)
                    .orElseThrow();

            published.markPublished();
        });

        jdbcTemplate.update(
                """
                UPDATE order_outbox
                SET created_at = ?
                WHERE id = ?
                """,
                LocalDateTime.now().minusMinutes(10),
                publishedId
        );

        // when
        List<OutboxAttentionItem> result =
                monitoringService.getAttentionRequired();

        List<Long> resultIds = result.stream()
                .map(OutboxAttentionItem::id)
                .toList();

        // then: 관리가 필요한 이벤트만 조회
        assertThat(resultIds)
                .contains(retryId, oldId);

        assertThat(resultIds)
                .doesNotContain(readyId, publishedId);

        // 재시도 횟수 확인
        OutboxAttentionItem retryItem = result.stream()
                .filter(item -> item.id().equals(retryId))
                .findFirst()
                .orElseThrow();

        assertThat(retryItem.retryCount())
                .isEqualTo(1);

        assertThat(retryItem.nextRetryAt())
                .isNotNull();
    }

    @Test
    void 조회_결과는_최대_20건으로_제한된다() {

        // 이전 테스트 데이터와 관계없이 Pageable 제한 검증
        List<OutboxAttentionItem> result =
                monitoringService.getAttentionRequired();

        assertThat(result.size())
                .isLessThanOrEqualTo(20);
    }
}