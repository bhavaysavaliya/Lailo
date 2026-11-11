-- Payment write model. The gateway is external, so this table records our view of the attempt and the
-- provider's reference for reconciliation; card and UPI credentials never reach Lailo.

CREATE TABLE payments (
    id                 UUID           PRIMARY KEY,
    order_id           UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    user_id            UUID           NOT NULL,
    amount             NUMERIC(12, 2) NOT NULL,
    currency           CHAR(3)        NOT NULL DEFAULT 'INR',
    method             VARCHAR(16)    NOT NULL,
    status             VARCHAR(16)    NOT NULL,
    provider           VARCHAR(32)    NOT NULL,
    -- Gateway's own id for the attempt; used to match webhooks and to reconcile settlements.
    provider_reference VARCHAR(128),
    failure_reason     VARCHAR(200),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    authorized_at      TIMESTAMPTZ,
    refunded_at        TIMESTAMPTZ,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'AUTHORIZED', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payments_method CHECK (method IN ('CARD', 'UPI', 'NETBANKING', 'WALLET', 'COD')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
);

-- A declined attempt is kept and a new row is created for the retry, so the order can hold several
-- historical payments but only one reference (orders.payment_id) to the live one.
CREATE INDEX ix_payments_order ON payments (order_id, created_at DESC);
CREATE INDEX ix_payments_user ON payments (user_id, created_at DESC);
-- A webhook arrives with the provider's reference only; this makes that lookup a single index hit and keeps
-- two attempts from claiming the same gateway transaction.
CREATE UNIQUE INDEX ux_payments_provider_reference ON payments (provider_reference)
    WHERE provider_reference IS NOT NULL;
