CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    total_amount NUMERIC(15, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT chk_orders_status
        CHECK (status IN ('CREATED', 'CANCELLED', 'COMPLETED')),

    CONSTRAINT chk_orders_total_amount
        CHECK (total_amount >= 0)
);

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL,

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id),

    CONSTRAINT fk_order_items_product
        FOREIGN KEY (product_id)
        REFERENCES products(id),

    CONSTRAINT chk_order_items_quantity
        CHECK (quantity > 0),

    CONSTRAINT chk_order_items_unit_price
        CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order_id
    ON order_items(order_id);