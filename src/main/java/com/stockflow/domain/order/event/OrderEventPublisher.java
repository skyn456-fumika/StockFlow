package com.stockflow.domain.order.event;

import com.stockflow.config.RabbitMqConfig;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
public class OrderEventPublisher {

    // 메시지를 발행하는 역할
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;

    public OrderEventPublisher(
            RabbitTemplate rabbitTemplate,
            JsonMapper jsonMapper
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {

        // 이후 다른 서비스가 메시지를 수신하더라도
        // Java 객체 직렬화에 의존하지 않도록 하기 위해서
        // 이벤트 객체를 JSON 문자열로 변환
        String payload = jsonMapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.ORDER_ROUTING_KEY,
                payload
        );
    }
}