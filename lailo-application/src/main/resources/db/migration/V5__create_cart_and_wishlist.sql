-- Cart and wishlist write models. Both aggregates are identified by the user: a shopper has exactly one
-- cart and one wishlist, so a separate parent row would add a join and buy nothing.
--
-- These two are the only commerce tables that cascade from users: a closed account should lose its cart and
-- wishlist, while its orders and payments must survive as financial records.

CREATE TABLE cart_items (
    id          UUID           PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id  UUID           NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    quantity    INTEGER        NOT NULL,
    -- Price when the item was added. Kept so the UI can show "price dropped since you added this";
    -- the order always re-reads the live price, so this snapshot is never charged.
    unit_price  NUMERIC(12, 2) NOT NULL,
    currency    CHAR(3)        NOT NULL DEFAULT 'INR',
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    version     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_cart_items_quantity CHECK (quantity > 0 AND quantity <= 20),
    CONSTRAINT ck_cart_items_price CHECK (unit_price > 0)
);

-- Adding a product already in the cart raises its quantity instead of creating a second line.
CREATE UNIQUE INDEX ux_cart_items_user_product ON cart_items (user_id, product_id);
CREATE INDEX ix_cart_items_user ON cart_items (user_id, created_at);

CREATE TABLE wishlist_items (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id  UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    note        VARCHAR(280),
    created_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0
);

-- A product is either saved or not, so adding it twice is idempotent rather than an error.
CREATE UNIQUE INDEX ux_wishlist_items_user_product ON wishlist_items (user_id, product_id);
CREATE INDEX ix_wishlist_items_user ON wishlist_items (user_id, created_at DESC);
