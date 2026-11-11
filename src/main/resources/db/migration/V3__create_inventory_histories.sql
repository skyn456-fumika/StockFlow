CREATE TABLE inventory_histories (
    id BIGSERIAL PRIMARY KEY,

    inventory_id BIGINT NOT NULL,

    change_type VARCHAR(20) NOT NULL,

    quantity_change INTEGER NOT NULL,

    quantity_after INTEGER NOT NULL,

    created_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_inventory_histories_inventory
        FOREIGN KEY (inventory_id)
        REFERENCES inventories(id),

    CONSTRAINT chk_inventory_histories_type
        CHECK (change_type IN ('INITIAL', 'INBOUND', 'OUTBOUND')),

    CONSTRAINT chk_inventory_histories_quantity
        CHECK (quantity_after >= 0),

    CONSTRAINT chk_inventory_histories_change
        CHECK (
            (change_type = 'INITIAL' AND quantity_change >= 0)
            OR
            (change_type = 'INBOUND' AND quantity_change > 0)
            OR
            (change_type = 'OUTBOUND' AND quantity_change < 0)
        )
);

CREATE INDEX idx_inventory_histories_inventory_id
    ON inventory_histories(inventory_id, id DESC);