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

        Message message = MessageBuilder
                .withBody(
                        outbox.getPayload()
                                .getBytes(StandardCharsets.UTF_8)
                )
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(outbox.getEventId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();

        // Publisher Confirm + Returns 검증
        routingVerifier.sendAndVerify(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        // 발행 및 라우팅 확인에 성공한 경우에만 상태 변경
        outbox.markPublished();

        return true;
    }
}