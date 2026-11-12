package com.stockflow.rabbitmq;

import com.stockflow.domain.order.event.OrderCreatedEvent;
import com.stockflow.domain.order.event.OrderEventConsumer;
import com.stockflow.domain.order.event.OrderEventPublisher;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "stockflow.order.consumer.enabled=true"
})
@Testcontainers
// RabbitMq 통합 테스트
class RabbitMqConnectionTest {

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
    OrderEventPublisher publisher;

    @Autowired
    JsonMapper jsonMapper;

    // 기존 Consumer의 실제 동작을 유지하면서 메서드가 호출되었는지 검증
    // 일반 @MockitoBean은 실제 객체를 Mock으로 교체하지만,
    // @MockitoSpyBean은 실제 객체를 감싸서 동작을 관찰하는 방식
    @MockitoSpyBean
    OrderEventConsumer consumer;

    @Test
    void 주문_생성_이벤트를_발행하면_Consumer가_수신한다() {

        // given
        OrderCreatedEvent event = new OrderCreatedEvent(
                100L,
                LocalDateTime.now()
        );

        String expectedPayload =
                jsonMapper.writeValueAsString(event);

        // when
        publisher.publishOrderCreated(event);

        // then: 최대 10초 동안 Consumer 호출 확인
        // RabbitMQ 메시지는 비동기로 전달되므로
        // 발행 직후 Consumer가 실행됐다고 보장할 수 없음
        // 따라서 최대 10초 동안 해당 메서드가 호출되는지 기다리면서 검증
        // 보통 메시지를 수신하면 더 빨리 테스트가 종료
        verify(consumer, timeout(10000))
                .consume(expectedPayload);
    }
}