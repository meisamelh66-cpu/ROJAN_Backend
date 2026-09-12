-- Phase 5A.2 - User Profile Media. Extends the canonical Media System
-- Evolution v2 foundation (already live in production through V22) with a
-- second, typed owner kind: a user's own avatar / profile-cover image,
-- alongside the existing salon-owned rows. Purely additive - every
-- existing salon-owned row already satisfies the new CHECK constraint by
-- construction (salon_id IS NOT NULL AND user_id IS NULL is exactly its
-- current state), so no backfill is needed and no existing salon media,
-- specialist photo, service image, or document is touched.
--
-- Numbered V24 - production's confirmed real ceiling is V23 (salon_city).
-- This branch was rebuilt directly on the real production lineage
-- (release/v1.2.0-manager-dashboard-rbac-fix + the booking-discovery fix)
-- specifically to avoid the V18/V19 numbering collision an earlier,
-- differently-based branch would have produced - see
-- FINAL-MIGRATION-RELEASE-GATE.md for that analysis.

ALTER TABLE media_assets
    ALTER COLUMN salon_id DROP NOT NULL;

ALTER TABLE media_assets
    ADD COLUMN user_id UUID REFERENCES users (id);

ALTER TABLE media_assets
    ADD CONSTRAINT chk_media_assets_exactly_one_owner CHECK (
        (salon_id IS NOT NULL AND user_id IS NULL)
        OR (salon_id IS NULL AND user_id IS NOT NULL)
    );

CREATE INDEX idx_media_assets_user_id ON media_assets (user_id);

-- User profile media is read back one type at a time (the avatar, the
-- cover) during the "replace previous safely" cleanup.
CREATE INDEX idx_media_assets_user_id_media_type ON media_assets (user_id, media_type);

-- User profile identity slots - mirrors salons.logo_media_id /
-- salons.cover_media_id (V17). Nullable; NULL means "not set."
ALTER TABLE users
    ADD COLUMN avatar_media_id UUID REFERENCES media_assets (id);

ALTER TABLE users
    ADD COLUMN cover_media_id UUID REFERENCES media_assets (id);
