CREATE TABLE audit_events
(
    id           UUID         PRIMARY KEY,
    salon_id     UUID         NOT NULL REFERENCES salons (id),
    actor_id     UUID         REFERENCES users (id),
    actor_type   VARCHAR(20)  NOT NULL,
    action_type  VARCHAR(40)  NOT NULL,
    entity_type  VARCHAR(20)  NOT NULL,
    entity_id    UUID         NOT NULL,
    old_value    TEXT,
    new_value    TEXT,
    metadata     TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_salon_id ON audit_events (salon_id);
CREATE INDEX idx_audit_events_entity ON audit_events (entity_type, entity_id);
CREATE INDEX idx_audit_events_created_at ON audit_events (created_at);
