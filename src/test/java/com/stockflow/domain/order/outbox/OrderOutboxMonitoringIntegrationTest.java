package com.stockflow.domain.order.outbox;

import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
// PostgreSQL 기반 모니터링 통합 테스트
// 주문 생성, 재시도 예약, 발행 완료 처리가
// 실제 DB에 커밋된 상태에서 집계되는지 확인하기 위해서
// @Transactional X
class OrderOutboxMonitoringIntegrationTest {

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
    void 실제_DB에서_Outbox_상태별_건수를_정확히_집계한다() {

        // 기존 데이터가 있을 수 있으므로 최초 집계값 기록
        OutboxStatusSummary before = monitoringService.getSummary();

        // 1. 즉시 발행 가능한 이벤트
        Long readyId = createPendingOutbox(
                "모니터링 READY 상품"
        );

        // 2. 재시도 대기 중인 이벤트
        Long delayedId = createPendingOutbox(
                "모니터링 DELAYED 상품"
        );

        transactionTemplate.executeWithoutResult(status -> {

            OrderOutbox delayed = outboxRepository
                    .findByIdForUpdate(delayedId)
                    .orElseThrow();

            delayed.scheduleRetry(
                    LocalDateTime.now().plusMinutes(10)
            );
        });

        // 3. 발행 완료 이벤트
        Long publishedId = createPendingOutbox(
                "모니터링 PUBLISHED 상품"
        );

        transactionTemplate.executeWithoutResult(status -> {

            OrderOutbox published = outboxRepository
                    .findByIdForUpdate(publishedId)
                    .orElseThrow();

            published.markPublished();
        });

        // when
        OutboxStatusSummary after = monitoringService.getSummary();

        // then
        // 테스트 간 기존 데이터가 남아 있을 가능성을 고려해
        // 절대 건수가 아니라 테스트 전후의 증가량을 비교
        assertThat(after.pending())
                .isEqualTo(before.pending() + 2);

        assertThat(after.ready())
                .isEqualTo(before.ready() + 1);

        assertThat(after.delayed())
                .isEqualTo(before.delayed() + 1);

        assertThat(after.published())
                .isEqualTo(before.published() + 1);

        // 개별 DB 상태 확인
        assertThat(outboxRepository.findById(readyId)
                .orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(outboxRepository.findById(delayedId)
                .orElseThrow().getRetryCount())
                .isEqualTo(1);

        assertThat(outboxRepository.findById(publishedId)
                .orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void 데이터가_없거나_변경이_없으면_집계값은_유지된다() {

        OutboxStatusSummary first = monitoringService.getSummary();

        OutboxStatusSummary second = monitoringService.getSummary();

        assertThat(second).isEqualTo(first);
    }
}