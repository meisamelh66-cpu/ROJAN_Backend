-- Salon Completeness: marks a service category as one of the salon's "specialty
-- lines" (e.g. professional keratin services) - owner-entered, optional-but-
-- answered, never activation-blocking. Additive-only, same shape as V23's own
-- salons.city addition: every existing category simply defaults to "not a
-- specialty line" rather than requiring a backfill decision. No index - this
-- column is never filtered/sorted on its own (categories are always fetched
-- per-salon via the existing idx_service_categories_salon_id), same reasoning
-- service_categories.active already has no dedicated index of its own either.
ALTER TABLE service_categories ADD COLUMN is_specialty BOOLEAN NOT NULL DEFAULT FALSE;
