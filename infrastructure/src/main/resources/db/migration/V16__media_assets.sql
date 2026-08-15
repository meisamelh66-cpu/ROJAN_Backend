CREATE TABLE media_assets
(
    id           UUID PRIMARY KEY,
    salon_id     UUID          NOT NULL REFERENCES salons (id),
    owner_type   VARCHAR(16)   NOT NULL,
    owner_id     UUID          NOT NULL,
    media_type   VARCHAR(16)   NOT NULL,
    storage_key  VARCHAR(500)  NOT NULL,
    file_name    VARCHAR(255)  NOT NULL,
    mime_type    VARCHAR(100)  NOT NULL,
    file_size    BIGINT        NOT NULL,
    url          VARCHAR(1000) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Every access check is salon-scoped first (tenant isolation) - this index
-- is the one every real query path (list-for-salon, public gallery) hits.
CREATE INDEX idx_media_assets_salon_id ON media_assets (salon_id);

-- Public gallery/portfolio reads filter by salon + media_type together.
CREATE INDEX idx_media_assets_salon_id_media_type ON media_assets (salon_id, media_type);

-- Polymorphic owner lookup - unused by anything in this phase (only SALON
-- owner_type exists yet) but cheap to add now and exactly what a future
-- specialist-/service-image phase will need first.
CREATE INDEX idx_media_assets_owner ON media_assets (owner_type, owner_id);
