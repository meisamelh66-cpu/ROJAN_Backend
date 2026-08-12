CREATE TABLE specialist_services
(
    id            UUID PRIMARY KEY,
    specialist_id UUID NOT NULL REFERENCES specialists (id),
    service_id    UUID NOT NULL REFERENCES services (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (specialist_id, service_id)
);
CREATE INDEX idx_specialist_services_specialist_id ON specialist_services (specialist_id);
CREATE INDEX idx_specialist_services_service_id ON specialist_services (service_id);
