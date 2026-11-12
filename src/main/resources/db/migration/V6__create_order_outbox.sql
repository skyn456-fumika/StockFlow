CREATE TABLE order_outbox (
    id BIGSERIAL PRIMARY KEY,

    event_id UUID NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,

    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,

    CONSTRAINT uk_order_outbox_event_id
        UNIQUE (event_id),

    CONSTRAINT fk_order_outbox_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id),

    CONSTRAINT chk_order_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED'))
);

CREATE INDEX idx_order_outbox_pending
    ON order_outbox(id)
    WHERE status = 'PENDING';