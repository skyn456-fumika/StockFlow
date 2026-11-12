ALTER TABLE order_outbox
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at TIMESTAMP NULL;

CREATE INDEX idx_order_outbox_retry
    ON order_outbox (next_retry_at, id)
    WHERE status = 'PENDING';