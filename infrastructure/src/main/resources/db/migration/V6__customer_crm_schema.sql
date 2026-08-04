CREATE TABLE customers
(
    id           UUID PRIMARY KEY,
    salon_id     UUID         NOT NULL REFERENCES salons (id),
    user_id      UUID REFERENCES users (id),
    full_name    VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20),
    email        VARCHAR(255),
    company      VARCHAR(255),
    status       VARCHAR(16)  NOT NULL DEFAULT 'LEAD',
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (phone_number IS NOT NULL OR email IS NOT NULL)
);

CREATE INDEX idx_customers_salon_id ON customers (salon_id);
CREATE INDEX idx_customers_user_id ON customers (user_id);
-- Same-salon duplicate-phone guard - does not prevent cross-salon duplicates
-- (correct: the same person is a distinct CRM subject per salon).
CREATE UNIQUE INDEX uq_customers_salon_phone ON customers (salon_id, phone_number) WHERE phone_number IS NOT NULL;

CREATE TABLE customer_tags
(
    id          UUID PRIMARY KEY,
    customer_id UUID         NOT NULL REFERENCES customers (id),
    label       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_tags_customer_id ON customer_tags (customer_id);

CREATE TABLE customer_notes
(
    id          UUID          PRIMARY KEY,
    customer_id UUID          NOT NULL REFERENCES customers (id),
    author_id   UUID          NOT NULL REFERENCES users (id),
    text        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_notes_customer_id ON customer_notes (customer_id);

CREATE TABLE customer_activities
(
    id          UUID         PRIMARY KEY,
    customer_id UUID         NOT NULL REFERENCES customers (id),
    type        VARCHAR(32)  NOT NULL,
    description VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_activities_customer_id ON customer_activities (customer_id);
