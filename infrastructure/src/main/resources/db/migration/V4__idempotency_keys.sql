CREATE TABLE idempotency_keys
(
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    response_status  INTEGER      NOT NULL,
    response_body    TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (idempotency_key)
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
