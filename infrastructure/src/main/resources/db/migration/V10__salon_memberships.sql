CREATE TABLE salon_memberships
(
    id         UUID PRIMARY KEY,
    salon_id   UUID        NOT NULL REFERENCES salons (id),
    user_id    UUID        NOT NULL REFERENCES users (id),
    role       VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (salon_id, user_id)
);
CREATE INDEX idx_salon_memberships_salon_id ON salon_memberships (salon_id);
CREATE INDEX idx_salon_memberships_user_id ON salon_memberships (user_id);
