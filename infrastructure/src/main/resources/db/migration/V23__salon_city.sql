-- Public Salon Marketplace (Phase 1): a structured city field for browsing/filtering active
-- salons by city. Nullable, additive, and safe on existing data - every pre-existing row simply
-- has no city yet (an owner sets it later via the same profile-completion flow already used for
-- latitude/longitude); nothing here requires or assumes it is ever set, and no existing salon
-- becomes invalid or undiscoverable by its absence.
ALTER TABLE salons ADD COLUMN city VARCHAR(120) NULL;

-- The public marketplace listing filters by city - an index keeps that filter cheap as the salon
-- count grows, same reasoning as V22's own idx_media_assets_target_id.
CREATE INDEX idx_salons_city ON salons (city);
