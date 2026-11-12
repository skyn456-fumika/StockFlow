package com.stockflow.domain.order.outbox;

public class OutboxPublishException extends RuntimeException {

    private final Long outboxId;

    public OutboxPublishException(Long outboxId, Throwable cause) {
        super("Outbox 발행 실패. outboxId=" + outboxId, cause);
        this.outboxId = outboxId;
    }

    public Long getOutboxId() {
        return outboxId;
    }
}