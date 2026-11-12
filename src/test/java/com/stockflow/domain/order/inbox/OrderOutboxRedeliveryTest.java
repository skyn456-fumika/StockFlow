package com.stockflow.domain.order.inbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.config.RabbitRoutingVerifier;
import com.stockflow.domain.order.outbox.OrderOutboxMessage;

import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "stockflow.outbox.consumer.enabled=true",
        "stockflow.outbox.scheduling-enabled=false",
        "spring.rabbitmq.listener.simple.default-requeue-rejected=true"
})
@Testcontainers
// 실제 재전달 통합 테스트
class OrderOutboxRedeliveryTest {

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
    JsonMapper jsonMapper;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @MockitoSpyBean
    RabbitRoutingVerifier routingVerifier;

    @MockitoSpyBean
    OrderOutboxConsumer consumer;

    @MockitoBean
    OrderInboxService inboxService;

    @Test
    // DLQ 발행 실패 → Listener 예외 → RabbitMQ 재전달 → DLQ 재발행 성공
    void DLQ_발행이_처음_실패하면_원본_메시지를_재전달한다()
            throws Exception {

        UUID eventId = UUID.randomUUID();

        OrderOutboxMessage event = new OrderOutboxMessage(
                eventId,
                1L,
                "ORDER_CREATED",
                LocalDateTime.now()
        );

        Message message = MessageBuilder
                .withBody(
                        jsonMapper.writeValueAsString(event)
                                .getBytes(StandardCharsets.UTF_8)
                )
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .build();

        // Inbox는 계속 실패하도록 설정
        when(inboxService.process(any()))
                .thenThrow(new IllegalStateException("DB 장애"));

        AtomicInteger dlqAttempts = new AtomicInteger();

        // DLQ 발행 첫 번째는 실패, 두 번째는 실제 RabbitMQ 발행
        doAnswer(invocation -> {

            if (dlqAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException(
                        "첫 번째 DLQ 발행 및 라우팅 확인 실패"
                );
            }

            // 두 번째 시도부터 실제 RabbitRoutingVerifier 실행
            return invocation.callRealMethod();

        }).when(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.OUTBOX_DLX),
                eq(""),
                any(Message.class)
        );

        // 원본 메시지 발행
        rabbitTemplate.send(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        // 첫 번째 Consumer 호출 실패 후 재전달되어 두 번째 호출
        verify(consumer, timeout(10000).times(2))
                .consume(any(Message.class));

        // 두 번째 DLQ 발행은 정상적으로 완료되어야 함
        Message deadLetter = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_DLQ,
                10000
        );

        assertThat(deadLetter).isNotNull();

        assertThat(deadLetter.getMessageProperties().getMessageId())
                .isEqualTo(eventId.toString());

        assertThat(deadLetter.getBody())
                .isEqualTo(message.getBody());

        // 최초 수신 3회 + 재전달 후 3회
        verify(inboxService, timeout(10000).times(6))
                .process(any());

        assertThat(dlqAttempts.get()).isEqualTo(2);
    }
}