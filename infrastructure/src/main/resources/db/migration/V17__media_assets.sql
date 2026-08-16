CREATE TABLE media_assets
(
    id             UUID PRIMARY KEY,
    salon_id       UUID         NOT NULL REFERENCES salons (id),
    media_type     VARCHAR(16)  NOT NULL,
    storage_key    VARCHAR(500) NOT NULL UNIQUE,
    original_name  VARCHAR(255) NOT NULL,
    mime_type      VARCHAR(100) NOT NULL,
    file_size      BIGINT       NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    uploaded_by    UUID         NOT NULL REFERENCES users (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_assets_salon_id ON media_assets (salon_id);
CREATE INDEX idx_media_assets_salon_id_type_status ON media_assets (salon_id, media_type, status);
