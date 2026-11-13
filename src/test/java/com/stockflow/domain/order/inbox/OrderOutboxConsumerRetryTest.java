package com.stockflow.domain.order.inbox;

import com.stockflow.config.RabbitMqConfig;
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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "stockflow.outbox.consumer.enabled=true",
        "stockflow.outbox.scheduling-enabled=false"
})
@Testcontainers
// Consumer 재시도 통합 테스트
@org.junit.jupiter.api.Tag("integration")
class OrderOutboxConsumerRetryTest {

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
    RabbitTemplate rabbitTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    OrderInboxService inboxService;

    private Message createMessage() {
        OrderOutboxMessage event = new OrderOutboxMessage(
                UUID.randomUUID(),
                1L,
                "ORDER_CREATED",
                LocalDateTime.now()
        );

        String payload = jsonMapper.writeValueAsString(event);

        return MessageBuilder
                .withBody(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(event.eventId().toString())
                .build();
    }

    @Test
    void 두번_실패한_후_세번째에_성공하면_DLQ로_보내지_않는다() {

        // given
        Message message = createMessage();

        when(inboxService.process(any()))
                .thenThrow(new IllegalStateException("일시적 DB 오류 1"))
                .thenThrow(new IllegalStateException("일시적 DB 오류 2"))
                .thenReturn(true);

        // when
        rabbitTemplate.send(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        // then
        verify(inboxService, timeout(10000).times(3))
                .process(any());

        Message deadLetter = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_DLQ,
                500
        );

        assertThat(deadLetter).isNull();
    }

    @Test
    void 세번_모두_실패하면_DLQ로_전달한다() {

        // given
        Message message = createMessage();

        when(inboxService.process(any()))
                .thenThrow(new IllegalStateException("DB 장애"));

        // when
        rabbitTemplate.send(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        // then: DLQ로 이동할 때까지 대기
        Message deadLetter = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_DLQ,
                10000
        );

        assertThat(deadLetter).isNotNull();

        assertThat(deadLetter.getMessageProperties().getMessageId())
                .isEqualTo(message.getMessageProperties().getMessageId());

        assertThat(deadLetter.getBody())
                .isEqualTo(message.getBody());

        verify(inboxService, times(3))
                .process(any());
    }
}