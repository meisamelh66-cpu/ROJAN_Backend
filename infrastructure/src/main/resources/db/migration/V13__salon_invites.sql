CREATE TABLE salon_invites
(
    id          UUID PRIMARY KEY,
    salon_id    UUID        NOT NULL REFERENCES salons (id),
    role        VARCHAR(16) NOT NULL,
    token       VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL REFERENCES users (id),
    accepted_by UUID        REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (token)
);
CREATE INDEX idx_salon_invites_salon_id ON salon_invites (salon_id);
CREATE INDEX idx_salon_invites_token ON salon_invites (token);
