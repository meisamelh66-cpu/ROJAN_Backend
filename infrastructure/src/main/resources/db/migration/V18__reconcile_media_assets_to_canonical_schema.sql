-- Phase 12.5.2 dry assembly rehearsal — reconciles production's live
-- 03a3206-shaped media_assets/salon-identity columns into 3173d40's
-- canonical shape. Content already validated clean in the Phase 12.3
-- isolated rehearsal (restored production backup, applied V18-V21,
-- verified the salon_documents FK resolves against the new table).
--
-- Ordered per checkpoint §11.3:
--   1. Drop old FKs
--   2. Clear salon identity-media references (clean-cut — production
--      currently holds 10 real media_assets rows for one salon, none of
--      them ever successfully assigned as logo/cover, per the Phase 12.1
--      production verification)
--   3. Drop old media_assets (03a3206 shape)
--   4. Recreate media_assets (3173d40 shape)
--   5. Re-add FKs against the new table
--   6. Leave salons.logo_url untouched (separate, pre-existing legacy
--      column, confirmed empty on every salon in production — Phase 12.1)

ALTER TABLE salons DROP CONSTRAINT salons_logo_media_id_fkey;
ALTER TABLE salons DROP CONSTRAINT salons_cover_media_id_fkey;

UPDATE salons SET logo_media_id = NULL, cover_media_id = NULL;

DROP TABLE media_assets;

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

ALTER TABLE salons ADD CONSTRAINT salons_logo_media_id_fkey FOREIGN KEY (logo_media_id) REFERENCES media_assets (id);
ALTER TABLE salons ADD CONSTRAINT salons_cover_media_id_fkey FOREIGN KEY (cover_media_id) REFERENCES media_assets (id);
