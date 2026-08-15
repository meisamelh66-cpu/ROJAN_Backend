-- Salon Identity Foundation Phase B: Salon references MediaAsset ids, never
-- duplicates media data itself - existing logo_url (V11) is untouched and
-- stays the legacy fallback (see Salon.kt's assignIdentityMedia doc
-- comment); these are purely additive.
ALTER TABLE salons ADD COLUMN logo_media_id UUID REFERENCES media_assets (id);
ALTER TABLE salons ADD COLUMN cover_media_id UUID REFERENCES media_assets (id);
