ALTER TABLE salons ADD COLUMN slug VARCHAR(80);

-- Deterministic fallback for any salon rows that already exist before this
-- runs. Deliberately not slugifying the name in SQL - this platform's
-- Farsi-first audience means many names will not produce a meaningful Latin
-- slug even in application code, let alone here. Ops assigns a real slug via
-- PATCH /salons/{id}/slug for any row that ends up with this fallback shape,
-- before printing any QR material for that salon.
UPDATE salons SET slug = 'salon-' || substr(replace(id::text, '-', ''), 1, 10) WHERE slug IS NULL;

ALTER TABLE salons ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX uq_salons_slug ON salons (slug);
