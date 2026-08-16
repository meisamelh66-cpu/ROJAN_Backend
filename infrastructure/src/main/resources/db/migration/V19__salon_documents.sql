CREATE TABLE salon_documents
(
    id                   UUID PRIMARY KEY,
    salon_id             UUID        NOT NULL REFERENCES salons (id),
    media_asset_id       UUID        NOT NULL UNIQUE REFERENCES media_assets (id),
    document_type        VARCHAR(16) NOT NULL,
    verification_status  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expiry_date          DATE,
    uploaded_by          UUID        NOT NULL REFERENCES users (id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_salon_documents_salon_id ON salon_documents (salon_id);
CREATE INDEX idx_salon_documents_salon_id_status ON salon_documents (salon_id, verification_status);
-- Powers the future Platform Authority "documents awaiting review" queue -
-- built now, alongside the table, rather than added under review-tooling
-- time pressure later.
CREATE INDEX idx_salon_documents_pending_expiry ON salon_documents (expiry_date) WHERE verification_status = 'APPROVED';
