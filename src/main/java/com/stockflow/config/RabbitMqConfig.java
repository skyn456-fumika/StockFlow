package com.stockflow.config;

import org.springframework.amqp.core.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// RabbitMQ 설정 클래스
public class RabbitMqConfig {

    public static final String ORDER_EXCHANGE =
            "stockflow.order.exchange"; // Routing Key

    public static final String ORDER_QUEUE =
            "stockflow.order.queue";

    public static final String ORDER_ROUTING_KEY =
            "order.created";

    public static final String OUTBOX_QUEUE =
            "stockflow.order.outbox.queue";

    public static final String OUTBOX_ROUTING_KEY =
            "order.outbox";

    // Outbox 실패 메시지 보관용
    public static final String OUTBOX_DLX =
            "stockflow.order.outbox.dlx";

    public static final String OUTBOX_DLQ =
            "stockflow.order.outbox.dlq";

    @Bean
    // 메시지를 어느 큐로 전달할지 결정
    public DirectExchange orderExchange() {
        // Routing Key가 일치하는 큐에 메시지를 전달
        return new DirectExchange(
                ORDER_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    // 메시지를 Consumer가 처리할 때까지 보관
    public Queue orderQueue() {
        return new Queue(
                ORDER_QUEUE,
                true
        );
    }

    @Bean
    // Exchange와 Queue를 연결하는 규칙
    public Binding orderBinding(
            Queue orderQueue,
            DirectExchange orderExchange
    ) {
        return BindingBuilder
                .bind(orderQueue)
                .to(orderExchange)
                .with(ORDER_ROUTING_KEY);
    }

    @Bean
    public Queue outboxQueue() {
        return new Queue(OUTBOX_QUEUE, true);
    }

    @Bean
    public Binding outboxBinding(
            Queue outboxQueue,
            DirectExchange orderExchange
    ) {
        return BindingBuilder
                .bind(outboxQueue)
                .to(orderExchange)
                .with(OUTBOX_ROUTING_KEY);
    }

    @Bean
    public FanoutExchange outboxDeadLetterExchange() {
        return new FanoutExchange(OUTBOX_DLX, true, false);
    }

    @Bean
    public Queue outboxDeadLetterQueue() {
        return new Queue(OUTBOX_DLQ, true);
    }

    @Bean
    public Binding outboxDeadLetterBinding(
            Queue outboxDeadLetterQueue,
            FanoutExchange outboxDeadLetterExchange
    ) {
        return BindingBuilder
                .bind(outboxDeadLetterQueue)
                .to(outboxDeadLetterExchange);
    }
}