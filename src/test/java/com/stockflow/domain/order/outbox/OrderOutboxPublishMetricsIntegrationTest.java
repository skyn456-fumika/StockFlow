package com.stockflow.domain.order.outbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.config.RabbitRoutingVerifier;
import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import io.micrometer.core.instrument.MeterRegistry;

import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false",
        "stockflow.order.consumer.enabled=false",
        "stockflow.metrics.outbox.enabled=false"
})
@Testcontainers
// RabbitMQ 기반 Counter 통합 테스트
@Tag("integration")
class OrderOutboxPublishMetricsIntegrationTest {

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
    OrderOutboxScheduler scheduler;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    MeterRegistry registry;

    @MockitoSpyBean
    RabbitRoutingVerifier routingVerifier;

    private Long createOrder(String productName) {

        Long productId = productService.registerProduct(
                productName,
                new BigDecimal("10000"),
                10
        );

        return orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );
    }

    private double counterValue(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void 실제_발행_실패와_성공이_Counter에_반영된다() {

        // 테스트 시작 전 RabbitMQ 큐 정리
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitMqConfig.OUTBOX_QUEUE);
            return null;
        });

        Long firstOrderId = createOrder(
                "Counter 통합 테스트 실패 상품"
        );

        Long secondOrderId = createOrder(
                "Counter 통합 테스트 성공 상품"
        );

        OrderOutbox first = outboxRepository
                .findByOrderIdOrderByIdAsc(firstOrderId)
                .get(0);

        OrderOutbox second = outboxRepository
                .findByOrderIdOrderByIdAsc(secondOrderId)
                .get(0);

        // Counter는 누적값이므로 테스트 시작 시점 기록
        double successBefore = counterValue(
                "stockflow.outbox.publish.success"
        );

        double failureBefore = counterValue(
                "stockflow.outbox.publish.failure"
        );

        // Timer도 누적되는 값이므로 테스트 시작 전 값을 저장
        Timer publishTimer = registry.get(
                "stockflow.outbox.publish.duration"
        ).timer();

        long timerCountBefore = publishTimer.count();

        double timerTotalBefore =
                publishTimer.totalTime(TimeUnit.NANOSECONDS);

        // 첫 번째 이벤트만 강제로 발행 실패
        doAnswer(invocation -> {

            Message message = invocation.getArgument(2);

            String messageId =
                    message.getMessageProperties().getMessageId();

            if (first.getEventId().toString().equals(messageId)) {
                throw new IllegalStateException(
                        "RabbitMQ 발행 실패 테스트"
                );
            }

            return invocation.callRealMethod();

        }).when(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.OUTBOX_ROUTING_KEY),
                any(Message.class)
        );

        // 실제 발행 스케줄러 실행
        scheduler.publishPendingEvents();

        // Counter 증가량 검증
        assertThat(counterValue(
                "stockflow.outbox.publish.success"
        ) - successBefore).isEqualTo(1.0);

        assertThat(counterValue(
                "stockflow.outbox.publish.failure"
        ) - failureBefore).isEqualTo(1.0);

        // 실패 1회 + 성공 1회 = Timer 기록 2회
        assertThat(publishTimer.count() - timerCountBefore)
                .isEqualTo(2);

        // 실제 발행 처리 시간이 기록됐는지 확인
        double elapsedNanos =
                publishTimer.totalTime(TimeUnit.NANOSECONDS)
                        - timerTotalBefore;

        assertThat(elapsedNanos)
                .isGreaterThan(0.0);

        // 첫 번째 이벤트: 발행 실패 및 재시도 예약
        OrderOutbox failed = outboxRepository
                .findById(first.getId())
                .orElseThrow();

        assertThat(failed.getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(failed.getRetryCount())
                .isEqualTo(1);

        assertThat(failed.getNextRetryAt())
                .isNotNull();

        // 두 번째 이벤트: 정상 발행
        OrderOutbox published = outboxRepository
                .findById(second.getId())
                .orElseThrow();

        assertThat(published.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        // 실제 RabbitMQ 메시지 확인
        Message received = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                5000
        );

        assertThat(received).isNotNull();

        assertThat(
                received.getMessageProperties().getMessageId()
        ).isEqualTo(second.getEventId().toString());

        // 실패한 첫 번째 이벤트는 전송되지 않았어야 함
        assertThat(rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                500
        )).isNull();
    }

}