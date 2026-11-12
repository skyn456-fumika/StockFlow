package com.stockflow.domain.order.inbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.config.RabbitRoutingVerifier;
import com.stockflow.domain.order.outbox.OrderOutboxMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(
        prefix = "stockflow.outbox.consumer",
        name = "enabled",
        havingValue = "true"
)
public class OrderOutboxConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(OrderOutboxConsumer.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 200;

    private final OrderInboxService inboxService;
    private final JsonMapper jsonMapper;
    private final RabbitRoutingVerifier routingVerifier;

    public OrderOutboxConsumer(
            OrderInboxService inboxService,
            JsonMapper jsonMapper,
            RabbitRoutingVerifier routingVerifier
    ) {
        this.inboxService = inboxService;
        this.jsonMapper = jsonMapper;
        this.routingVerifier = routingVerifier;
    }

    @RabbitListener(queues = RabbitMqConfig.OUTBOX_QUEUE)
    public void consume(Message message) {

        OrderOutboxMessage event;

        try {
            String payload = new String(
                    message.getBody(),
                    StandardCharsets.UTF_8
            );

            event = jsonMapper.readValue(
                    payload,
                    OrderOutboxMessage.class
            );

        } catch (JacksonException e) {
            log.error(
                    "JSON 변환 실패. messageId={}",
                    message.getMessageProperties().getMessageId(),
                    e
            );

            sendToDlq(message);
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {

            try {
                inboxService.process(event);

                log.info(
                        "Consumer 처리 완료. eventId={}, attempt={}",
                        event.eventId(),
                        attempt
                );

                return;

            } catch (IllegalArgumentException e) {
                // 지원하지 않는 이벤트 등 재시도해도 해결되지 않는 오류
                log.error(
                        "잘못된 이벤트. 재시도 없이 DLQ 이동. eventId={}",
                        event.eventId(),
                        e
                );

                sendToDlq(message);
                return;

            } catch (RuntimeException e) {

                log.warn(
                        "Consumer 처리 실패. eventId={}, attempt={}/{}",
                        event.eventId(),
                        attempt,
                        MAX_ATTEMPTS,
                        e
                );

                if (attempt == MAX_ATTEMPTS) {
                    log.error(
                            "Consumer 재시도 횟수 초과. DLQ 이동. eventId={}",
                            event.eventId()
                    );

                    sendToDlq(message);
                    return;
                }

                try {
                    Thread.sleep(RETRY_DELAY_MS);

                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();

                    // ACK하지 않고 Listener에 실패를 전달
                    throw new IllegalStateException(
                            "Consumer 재시도 중 인터럽트 발생",
                            interrupted
                    );
                }
            }
        }
    }

    private void sendToDlq(Message message) {

        routingVerifier.sendAndVerify(
                RabbitMqConfig.OUTBOX_DLX,
                "",
                message
        );

        log.info(
                "DLQ 발행 및 라우팅 확인 완료. messageId={}",
                message.getMessageProperties().getMessageId()
        );
    }
}