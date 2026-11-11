-- Order write model. Every monetary and descriptive value is a snapshot taken when the order was placed:
-- renaming or repricing a product must never change what a shopper sees they bought.
--
-- No foreign key to users on purpose - an order is a financial record and outlives the account.

CREATE TABLE orders (
    id                     UUID           PRIMARY KEY,
    -- Human-readable reference used in emails, texts and support calls.
    order_number           VARCHAR(24)    NOT NULL,
    user_id                UUID           NOT NULL,
    status                 VARCHAR(16)    NOT NULL,
    items_total            NUMERIC(12, 2) NOT NULL,
    shipping_fee           NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount           NUMERIC(12, 2) NOT NULL,
    currency               CHAR(3)        NOT NULL DEFAULT 'INR',
    payment_method         VARCHAR(16)    NOT NULL,
    -- The payment currently in play; a failed attempt is kept in payments and replaced here on retry.
    payment_id             UUID,

    -- Delivery address as entered at checkout (prefix matches the embedded ShippingAddress value object).
    ship_full_name         VARCHAR(120)   NOT NULL,
    ship_phone             VARCHAR(16)    NOT NULL,
    ship_line1             VARCHAR(200)   NOT NULL,
    ship_line2             VARCHAR(200),
    ship_city              VARCHAR(120)   NOT NULL,
    ship_state             VARCHAR(120)   NOT NULL,
    ship_postal_code       VARCHAR(12)    NOT NULL,
    ship_country           CHAR(2)        NOT NULL DEFAULT 'IN',

    -- One timestamp per state entered, which is also the audit trail of the fulfilment pipeline.
    placed_at              TIMESTAMPTZ    NOT NULL,
    confirmed_at           TIMESTAMPTZ,
    packed_at              TIMESTAMPTZ,
    shipped_at             TIMESTAMPTZ,
    delivered_at           TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    cancel_reason          VARCHAR(64),

    courier_name           VARCHAR(80),
    tracking_number        VARCHAR(80),
    expected_delivery_date DATE,

    updated_at             TIMESTAMPTZ    NOT NULL,
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING_PAYMENT', 'CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_orders_method CHECK (payment_method IN ('CARD', 'UPI', 'NETBANKING', 'WALLET', 'COD')),
    CONSTRAINT ck_orders_totals CHECK (items_total > 0 AND shipping_fee >= 0 AND total_amount > 0)
);

CREATE UNIQUE INDEX ux_orders_order_number ON orders (order_number);
-- Serves "my orders", newest first.
CREATE INDEX ix_orders_user_placed ON orders (user_id, placed_at DESC);
-- Serves the fulfilment queues: everything still waiting to be shipped.
CREATE INDEX ix_orders_to_be_shipped ON orders (placed_at)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED', 'PACKED');

CREATE TABLE order_items (
    id            UUID           PRIMARY KEY,
    order_id      UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    -- No foreign key to products: the line is a snapshot and must survive the product being removed.
    product_id    UUID           NOT NULL,
    product_name  VARCHAR(200)   NOT NULL,
    product_slug  VARCHAR(220)   NOT NULL,
    image_url     VARCHAR(1024),
    quantity      INTEGER        NOT NULL,
    unit_price    NUMERIC(12, 2) NOT NULL,
    line_total    NUMERIC(12, 2) NOT NULL,
    currency      CHAR(3)        NOT NULL DEFAULT 'INR',
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_price CHECK (unit_price > 0 AND line_total > 0)
);

CREATE UNIQUE INDEX ux_order_items_order_product ON order_items (order_id, product_id);
CREATE INDEX ix_order_items_order ON order_items (order_id);
