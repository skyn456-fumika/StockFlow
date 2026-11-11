CREATE TABLE inventories (
    id BIGSERIAL PRIMARY KEY,

    product_id BIGINT NOT NULL UNIQUE,

    quantity INTEGER NOT NULL,

    version BIGINT NOT NULL DEFAULT 0,

    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_inventories_product
        FOREIGN KEY (product_id)
        REFERENCES products(id),

    CONSTRAINT chk_inventories_quantity
        CHECK (quantity >= 0)
);