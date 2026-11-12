package com.stockflow.domain.order.event;

import com.stockflow.config.RabbitMqConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(
        prefix = "stockflow.order.consumer",
        name = "enabled",
        havingValue = "true"
)
public class OrderEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(OrderEventConsumer.class);

    private final JsonMapper jsonMapper;

    public OrderEventConsumer(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // RabbitMQ 큐에 메시지가 들어오면
    // @RabbitListener가 자동으로 메시지를 받아 처리
    @RabbitListener(queues = RabbitMqConfig.ORDER_QUEUE)
    public void consume(String payload) {

        OrderCreatedEvent event = jsonMapper.readValue(
                payload,
                OrderCreatedEvent.class
        );

        log.info(
                "주문 생성 이벤트 수신. orderId={}, occurredAt={}",
                event.orderId(),
                event.occurredAt()
        );
    }
}