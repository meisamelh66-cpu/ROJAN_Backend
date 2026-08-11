CREATE TABLE salon_follows
(
    id          UUID PRIMARY KEY,
    customer_id UUID        NOT NULL REFERENCES users (id),
    salon_id    UUID        NOT NULL REFERENCES salons (id),
    status      VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, salon_id)
);
CREATE INDEX idx_salon_follows_customer_id ON salon_follows (customer_id);
CREATE INDEX idx_salon_follows_salon_id ON salon_follows (salon_id);

CREATE TABLE salon_favorites
(
    id          UUID PRIMARY KEY,
    customer_id UUID        NOT NULL REFERENCES users (id),
    salon_id    UUID        NOT NULL REFERENCES salons (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, salon_id)
);
CREATE INDEX idx_salon_favorites_customer_id ON salon_favorites (customer_id);
CREATE INDEX idx_salon_favorites_salon_id ON salon_favorites (salon_id);
