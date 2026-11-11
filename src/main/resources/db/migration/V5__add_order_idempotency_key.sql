ALTER TABLE orders
ADD COLUMN idempotency_key VARCHAR(100);

ALTER TABLE orders
ADD CONSTRAINT uk_orders_idempotency_key
UNIQUE (idempotency_key);