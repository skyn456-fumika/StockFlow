package com.stockflow.config;

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

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "stockflow.outbox.consumer.enabled=false",
        "stockflow.outbox.scheduling-enabled=false"
})
@Testcontainers
class RabbitRoutingVerifierTest {

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
    RabbitRoutingVerifier verifier;

    @Autowired
    RabbitTemplate rabbitTemplate;

    private Message createMessage() {
        return MessageBuilder
                .withBody(
                        "라우팅 테스트"
                                .getBytes(StandardCharsets.UTF_8)
                )
                .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                .setMessageId(UUID.randomUUID().toString())
                .build();
    }

    @Test
    void 정상_라우팅이면_발행에_성공한다() {

        Message message = createMessage();

        assertThatCode(() -> verifier.sendAndVerify(
                RabbitMqConfig.OUTBOX_DLX,
                "",
                message
        )).doesNotThrowAnyException();

        Message received = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_DLQ,
                5000
        );

        assertThat(received).isNotNull();
        assertThat(received.getBody())
                .isEqualTo(message.getBody());
    }

    @Test
    // Exchange는 존재하지만 어떤 Queue에도 연결되지 않는 Routing Key를 일부러 사용
    // 이 경우 RabbitMQ는 메시지를 받을 수 있지만 정상적으로 라우팅할 수는 없음
    // mandatory=true라면 반환 메시지가 발생
    // RabbitRoutingVerifier가 예외를 던짐
    void 라우팅할_Queue가_없으면_실패를_감지한다() {

        Message message = createMessage();

        assertThatThrownBy(() -> verifier.sendAndVerify(
                RabbitMqConfig.ORDER_EXCHANGE,
                "routing.key.does.not.exist",
                message
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("라우팅 실패");
    }
}