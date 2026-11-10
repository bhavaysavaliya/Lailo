-- Catalogue write model (CQRS): commands write here, the outbox relays events, MongoDB serves reads.
-- Replaces the sample table created by V1.

CREATE TABLE products (
    id             UUID          PRIMARY KEY,
    slug           VARCHAR(220)  NOT NULL,
    name           VARCHAR(200)  NOT NULL,
    description    VARCHAR(4000),
    brand          VARCHAR(120),
    category       VARCHAR(120)  NOT NULL,
    price          NUMERIC(12, 2) NOT NULL,
    currency       CHAR(3)       NOT NULL DEFAULT 'INR',
    stock_quantity INTEGER       NOT NULL DEFAULT 0,
    status         VARCHAR(16)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_products_price CHECK (price > 0),
    CONSTRAINT ck_products_stock CHECK (stock_quantity >= 0),
    CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

-- The slug carries an id suffix, so it is unique by construction; the index keeps that guarantee in the database.
CREATE UNIQUE INDEX ux_products_slug ON products (slug);
CREATE INDEX ix_products_category ON products (category) WHERE status = 'ACTIVE';

-- The V1 sample table had no CQRS wiring and no consumers; the catalogue above takes its place.
DROP TABLE IF EXISTS lailo_products;
