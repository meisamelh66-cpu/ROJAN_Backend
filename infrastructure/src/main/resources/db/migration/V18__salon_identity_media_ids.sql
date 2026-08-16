ALTER TABLE salons ADD COLUMN logo_media_id UUID REFERENCES media_assets (id);
ALTER TABLE salons ADD COLUMN cover_media_id UUID REFERENCES media_assets (id);

-- Deliberately additive only. salons.logo_url (the pre-existing string
-- column) is untouched here - System 1's approved migration plan drops it
-- in a later, separate migration only after a manual backfill of any
-- legacy non-null logo_url rows into a real media_assets row is run and
-- verified. Dropping it in the same migration as these additive columns
-- would risk losing that data with no verification window.
