CREATE TABLE payment_gateway_configs
(
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    salon_id               UUID        NOT NULL UNIQUE REFERENCES salons (id),
    provider               VARCHAR(32) NOT NULL,
    encrypted_merchant_id  TEXT        NOT NULL,
    masked_merchant_id     VARCHAR(64) NOT NULL,
    is_active              BOOLEAN     NOT NULL DEFAULT false,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No separate index on salon_id: the UNIQUE constraint above already creates one.
