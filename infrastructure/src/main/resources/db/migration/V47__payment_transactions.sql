CREATE TABLE payment_transactions
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    salon_id             UUID        NOT NULL REFERENCES salons (id),
    booking_id           UUID        REFERENCES bookings (id),
    customer_id          UUID        NOT NULL REFERENCES users (id),
    method               VARCHAR(16) NOT NULL,
    amount               BIGINT      NOT NULL,
    currency             VARCHAR(3)  NOT NULL DEFAULT 'IRR',
    status               VARCHAR(20) NOT NULL,
    gateway_provider     VARCHAR(32),
    gateway_authority    VARCHAR(128),
    gateway_reference_id VARCHAR(128),
    idempotency_key      VARCHAR(128),
    failure_reason       VARCHAR(500),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (amount > 0),
    CHECK (currency = 'IRR')
);

CREATE INDEX idx_payment_transactions_booking_id ON payment_transactions (booking_id);
CREATE INDEX idx_payment_transactions_customer_id ON payment_transactions (customer_id);
CREATE INDEX idx_payment_transactions_salon_id ON payment_transactions (salon_id);
CREATE INDEX idx_payment_transactions_status_created_at ON payment_transactions (status, created_at);

CREATE UNIQUE INDEX uq_payment_transactions_gateway_authority
    ON payment_transactions (gateway_authority)
    WHERE gateway_authority IS NOT NULL;

CREATE UNIQUE INDEX uq_payment_transactions_idempotency_key
    ON payment_transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
