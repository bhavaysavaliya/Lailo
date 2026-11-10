-- Product images and videos. Bytes live in the S3-compatible object store (SeaweedFS); only metadata is here.
-- A row is created when the upload URL is signed and becomes READY once the bytes are verified, so an
-- abandoned upload never reaches the catalogue.

CREATE TABLE product_media (
    id            UUID         PRIMARY KEY,
    product_id    UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    type          VARCHAR(8)   NOT NULL,
    object_key    VARCHAR(512) NOT NULL,
    content_type  VARCHAR(128) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    status        VARCHAR(8)   NOT NULL,
    position      INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    ready_at      TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_product_media_type CHECK (type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_product_media_status CHECK (status IN ('PENDING', 'READY')),
    CONSTRAINT ck_product_media_size CHECK (size_bytes > 0)
);

-- One object per row, so a repeated confirmation can never attach the same blob twice.
CREATE UNIQUE INDEX ux_product_media_object_key ON product_media (object_key);
CREATE INDEX ix_product_media_product ON product_media (product_id, position);
-- Lets a sweeper find uploads that were signed but never confirmed.
CREATE INDEX ix_product_media_pending ON product_media (created_at) WHERE status = 'PENDING';
