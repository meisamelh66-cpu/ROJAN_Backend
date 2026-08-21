-- Media System Evolution v2: lets one media_assets row belong to a specific
-- non-salon entity (a Specialist for PORTFOLIO, a Service for SERVICE_IMAGE)
-- instead of only ever being salon-flat, and gives gallery/portfolio/service
-- image rows a stable, caller-controlled sort order. Both columns are
-- additive and safe on existing data: target_id defaults NULL (every
-- pre-existing row stays salon-scoped, exactly as before), display_order
-- defaults 0 (existing rows sort by their pre-existing createdAt tiebreak
-- until explicitly reordered).
ALTER TABLE media_assets ADD COLUMN target_id UUID NULL;
ALTER TABLE media_assets ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0;

-- Reads are always "every media row for this target" (a specialist's
-- portfolio, a service's images) - mirrors idx_media_assets_salon_id_type_status's
-- existing reasoning, just for the new per-target query shape.
CREATE INDEX idx_media_assets_target_id ON media_assets (target_id);
