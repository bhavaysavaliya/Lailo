-- Write model for passwordless (OTP) authentication.

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(254),
    phone           VARCHAR(16),
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    last_login_at   TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_identifier_present CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);
-- One account per email / phone (stored normalised: lower-case email, E.164 phone).
CREATE UNIQUE INDEX ux_users_email ON users (email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX ux_users_phone ON users (phone) WHERE phone IS NOT NULL;

-- Refresh-token sessions. Only SHA-256 hashes are stored; family_id groups one rotation chain.
CREATE TABLE user_sessions (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id       UUID         NOT NULL,
    token_hash      CHAR(64)     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(32),
    replaced_by     UUID,
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_user_sessions_token_hash ON user_sessions (token_hash);
CREATE INDEX ix_user_sessions_family ON user_sessions (family_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_user_sessions_user ON user_sessions (user_id);

-- Transactional outbox, relayed to Kafka.
CREATE TABLE outbox_messages (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON outbox_messages (created_at) WHERE published_at IS NULL;
