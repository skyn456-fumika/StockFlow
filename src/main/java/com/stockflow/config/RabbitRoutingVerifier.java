package com.stockflow.config;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RabbitRoutingVerifier {

    private final RabbitTemplate rabbitTemplate;

    public RabbitRoutingVerifier(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendAndVerify(
            String exchange,
            String routingKey,
            Message message
    ) {
        // CorrelationData는 개별 발행 시도의 확인 결과를 추적
        CorrelationData correlationData = new CorrelationData(
                UUID.randomUUID().toString()
        );

        rabbitTemplate.send(
                exchange,
                routingKey,
                message,
                correlationData
        );

        try {
            CorrelationData.Confirm confirm =
                    correlationData.getFuture()
                            .get(5, TimeUnit.SECONDS);

            if (!confirm.ack()) {
                throw new IllegalStateException(
                        "RabbitMQ 발행 확인 실패: "
                                + confirm.reason()
                );
            }

            ReturnedMessage returned = correlationData.getReturned();

            if (returned != null) {
                throw new IllegalStateException(
                        "RabbitMQ 라우팅 실패: "
                                + returned.getReplyText()
                );
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "RabbitMQ 발행 확인 중 인터럽트 발생",
                    e
            );

        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {

            throw new IllegalStateException(
                    "RabbitMQ 발행 확인 실패 또는 시간 초과",
                    e
            );
        }
    }
}