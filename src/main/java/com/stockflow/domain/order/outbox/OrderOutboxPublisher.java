package com.stockflow.domain.order.outbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.config.RabbitRoutingVerifier;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class OrderOutboxPublisher {

    private final OrderOutboxRepository outboxRepository;
    private final RabbitRoutingVerifier routingVerifier;

    public OrderOutboxPublisher(
            OrderOutboxRepository outboxRepository,
            RabbitRoutingVerifier routingVerifier
    ) {
        this.outboxRepository = outboxRepository;
        this.routingVerifier = routingVerifier;
    }

    @Transactional
    public boolean publishOne(Long outboxId) {

        OrderOutbox outbox = outboxRepository
                .findByIdForUpdate(outboxId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Outbox 이벤트를 찾을 수 없습니다."
                        )
                );

        if (outbox.getStatus() == OutboxStatus.PUBLISHED) {
            return false;
        }

        // 자체적으로 트랜잭션을 시작하지 않기 때문에
        // 반드시 이미 트랜잭션과 행 잠금이 확보된 상태에서 호출해야 함
        publishLocked(outbox);

        return true;
    }

    // 이미 DB 행 잠금을 획득한 트랜잭션에서 호출
    void publishLocked(OrderOutbox outbox) {

        Message message = MessageBuilder
                .withBody(
                        outbox.getPayload()
                                .getBytes(StandardCharsets.UTF_8)
                )
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(outbox.getEventId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();

        routingVerifier.sendAndVerify(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        outbox.markPublished();
    }
}