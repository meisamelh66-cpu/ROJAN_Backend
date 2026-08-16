CREATE TABLE salon_verifications
(
    id                UUID PRIMARY KEY,
    salon_id          UUID         NOT NULL REFERENCES salons (id),
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    submitted_by      UUID         NOT NULL REFERENCES users (id),
    submitted_at      TIMESTAMPTZ  NOT NULL,
    reviewed_by       UUID         REFERENCES users (id),
    reviewed_at       TIMESTAMPTZ,
    rejection_reason  VARCHAR(1000),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most one open case per salon, enforced by the database rather than an
-- application-level race-prone check.
CREATE UNIQUE INDEX idx_salon_verifications_one_active
    ON salon_verifications (salon_id)
    WHERE status IN ('PENDING', 'UNDER_REVIEW');

CREATE INDEX idx_salon_verifications_salon_id ON salon_verifications (salon_id);
CREATE INDEX idx_salon_verifications_status ON salon_verifications (status);

CREATE TABLE salon_verification_documents
(
    id               UUID PRIMARY KEY,
    verification_id  UUID NOT NULL REFERENCES salon_verifications (id),
    document_id      UUID NOT NULL REFERENCES salon_documents (id),
    attached_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (verification_id, document_id)
);

CREATE INDEX idx_svd_verification_id ON salon_verification_documents (verification_id);
CREATE INDEX idx_svd_document_id ON salon_verification_documents (document_id);
