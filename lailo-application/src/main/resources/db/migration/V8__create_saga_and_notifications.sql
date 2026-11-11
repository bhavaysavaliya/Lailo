-- Cross-cutting infrastructure: saga state and notification idempotency. Both are shared by every feature,
-- which is why they live beside the outbox rather than inside one feature's schema.

CREATE TABLE saga_instances (
    id             UUID         PRIMARY KEY,
    saga_type      VARCHAR(48)  NOT NULL,
    -- Business key the saga follows, e.g. the order id.
    correlation_id VARCHAR(64)  NOT NULL,
    -- Current step. Transitions are conditional on it, which is what makes a redelivered event a no-op.
    step           VARCHAR(48)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    last_error     VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_saga_status CHECK (status IN
        ('RUNNING', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED'))
);

-- One run per business key: the unique index is what makes "start the saga" idempotent.
CREATE UNIQUE INDEX ux_saga_instances_type_correlation ON saga_instances (saga_type, correlation_id);
-- Lets a monitor find runs that are stuck between steps.
CREATE INDEX ix_saga_instances_unfinished ON saga_instances (updated_at)
    WHERE status IN ('RUNNING', 'COMPENSATING');

CREATE TABLE notification_deliveries (
    id             UUID         PRIMARY KEY,
    -- Caller-chosen idempotency key, normally the id of the event that triggered the notification.
    dedupe_key     VARCHAR(128) NOT NULL,
    channel        VARCHAR(16)  NOT NULL,
    -- Masked destination: enough to debug one delivery, not enough to leak a contact list.
    destination    VARCHAR(128) NOT NULL,
    subject        VARCHAR(200),
    status         VARCHAR(16)  NOT NULL,
    failure_reason VARCHAR(500),
    attempts       INTEGER      NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL,
    sent_at        TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- The claim: events are delivered at least once, and this index is what stops a second "order placed" text.
CREATE UNIQUE INDEX ux_notification_deliveries_key_channel ON notification_deliveries (dedupe_key, channel);
-- Lets a retry job or alert find deliveries no consumer managed to send.
CREATE INDEX ix_notification_deliveries_failed ON notification_deliveries (created_at) WHERE status = 'FAILED';
