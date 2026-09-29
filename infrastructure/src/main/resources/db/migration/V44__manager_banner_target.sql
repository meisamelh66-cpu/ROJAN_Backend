-- Banner Management: adds MANAGER as a valid target, additively. V40__banners.sql is already
-- applied in production and is never edited retroactively - this widens its CHECK constraint
-- instead. V43 is already taken by V43__app_release_hardening.sql on this line, hence V44.
-- Existing SITE/CUSTOMER/DESKTOP rows are untouched; no data migration needed since this only
-- relaxes a constraint, never narrows or reinterprets one.
ALTER TABLE banners DROP CONSTRAINT banners_target_check;
ALTER TABLE banners ADD CONSTRAINT banners_target_check CHECK (target IN ('SITE', 'CUSTOMER', 'DESKTOP', 'MANAGER'));
