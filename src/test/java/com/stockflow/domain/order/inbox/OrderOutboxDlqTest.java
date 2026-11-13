package com.stockflow.domain.order.inbox;

import com.stockflow.config.RabbitMqConfig;

import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Tag("integration")
class OrderOutboxDlqTest {

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
            new RabbitMQContainer(
                    "rabbitmq:4.3-management-alpine"
            );

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Test
    void 실패_메시지를_DLQ에_전달할_수_있다() {

        // given
        String eventId = UUID.randomUUID().toString();
        String payload = "DLQ 테스트 메시지";

        Message message = MessageBuilder
                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                .setMessageId(eventId)
                .build();

        // when: 실패 메시지 전용 Exchange로 전달
        rabbitTemplate.send(
                RabbitMqConfig.OUTBOX_DLX,
                "",
                message
        );

        // then: DLQ에서 직접 수신
        Message received = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_DLQ,
                5000
        );

        assertThat(received).isNotNull();

        assertThat(new String(
                received.getBody(),
                StandardCharsets.UTF_8
        )).isEqualTo(payload);

        assertThat(received.getMessageProperties().getMessageId())
                .isEqualTo(eventId);
    }
}