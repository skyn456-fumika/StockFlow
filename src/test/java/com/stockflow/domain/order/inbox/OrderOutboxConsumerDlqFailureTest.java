package com.stockflow.domain.order.inbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.config.RabbitRoutingVerifier;
import com.stockflow.domain.order.outbox.OrderOutboxMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderOutboxConsumerDlqFailureTest {

    private OrderInboxService inboxService;
    private JsonMapper jsonMapper;
    private RabbitRoutingVerifier routingVerifier;
    private OrderOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        inboxService = mock(OrderInboxService.class);
        jsonMapper = mock(JsonMapper.class);
        routingVerifier = mock(RabbitRoutingVerifier.class);

        consumer = new OrderOutboxConsumer(
                inboxService,
                jsonMapper,
                routingVerifier
        );
    }

    @Test
    void Inbox_처리와_DLQ_발행이_모두_실패하면_예외를_전파한다() {

        UUID eventId = UUID.randomUUID();

        OrderOutboxMessage event = new OrderOutboxMessage(
                eventId,
                1L,
                "ORDER_CREATED",
                LocalDateTime.now()
        );

        Message message = MessageBuilder
                .withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .build();

        when(jsonMapper.readValue(
                anyString(),
                eq(OrderOutboxMessage.class)
        )).thenReturn(event);

        when(inboxService.process(any()))
                .thenThrow(new IllegalStateException("DB 연결 실패"));

        // DLQ 라우팅 검증 실패
        doThrow(new IllegalStateException("RabbitMQ 라우팅 실패"))
                .when(routingVerifier)
                .sendAndVerify(
                        eq(RabbitMqConfig.OUTBOX_DLX),
                        eq(""),
                        any(Message.class)
                );

        assertThatThrownBy(() -> consumer.consume(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("라우팅 실패");

        verify(inboxService, times(3))
                .process(any());

        verify(routingVerifier, times(1))
                .sendAndVerify(
                        eq(RabbitMqConfig.OUTBOX_DLX),
                        eq(""),
                        any(Message.class)
                );
    }
}