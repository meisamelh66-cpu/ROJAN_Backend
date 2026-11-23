-- App Release Update-Check Fix: decouples the real Android versionCode a
-- caller must be compared against (client_version_code) from this table's
-- own versionCode, which is only an identity/uniqueness key (target +
-- channel + version_code) and is not guaranteed to equal the real Android
-- build number - see ai.rojan.backend.domain.apprelease.AppRelease's own
-- doc comment for the full explanation and the real incident (ROJAN
-- Manager 1.0.6, Android versionCode 6, this table's own versionCode had
-- to be 7) this fixes.
--
-- Purely additive, non-destructive: adds one new column and backfills
-- every existing row to client_version_code = version_code, the correct
-- value for every row where the two numbers already coincide (true for
-- every app except the one already-known, already-live Manager
-- 1.0.6/versionCode=7 row). No existing column, row, or value is changed
-- or removed. Correcting that one specific row's client_version_code to
-- 6 is a separate, explicit, reviewable follow-up action (an UpdateAppRelease
-- API call), not part of this schema migration - a migration must never
-- bake in which specific row is wrong.
ALTER TABLE app_releases ADD COLUMN client_version_code INTEGER;

UPDATE app_releases SET client_version_code = version_code WHERE client_version_code IS NULL;

ALTER TABLE app_releases ALTER COLUMN client_version_code SET NOT NULL;
