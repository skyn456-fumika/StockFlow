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

@SpringBootTest(properties = {
        "stockflow.outbox.consumer.enabled=true",
        "stockflow.outbox.scheduling-enabled=false"
})
@Testcontainers
// 자동 DLQ 테스트
class OrderOutboxConsumerDlqTest {

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
    void 잘못된_JSON_메시지는_Consumer가_DLQ로_전달한다() {

        // given: JSON 문법이 잘못된 메시지
        String invalidJson = "{invalid-json";
        String messageId = UUID.randomUUID().toString();

        Message message = MessageBuilder
                .withBody(
                        invalidJson.getBytes(StandardCharsets.UTF_8)
                )
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(messageId)
                .build();

        // when: 정상 Outbox Queue로 메시지 발행
        rabbitTemplate.send(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        // then: Consumer가 JSON 오류를 감지하여 DLQ에 전달
        Message deadLetter = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_DLQ,
                10000
        );

        assertThat(deadLetter).isNotNull();

        assertThat(
                new String(
                        deadLetter.getBody(),
                        StandardCharsets.UTF_8
                )
        ).isEqualTo(invalidJson);

        assertThat(
                deadLetter.getMessageProperties().getMessageId()
        ).isEqualTo(messageId);
    }
}