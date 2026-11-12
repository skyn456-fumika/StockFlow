CREATE TABLE processed_order_events (
    event_id UUID PRIMARY KEY,

    order_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,

    processed_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_processed_order_events_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
);

CREATE INDEX idx_processed_order_events_order_id
    ON processed_order_events(order_id);