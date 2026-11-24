-- Manager 1.0.7 Update-Check Data Correction: the Admin UI's create/edit
-- forms have never exposed client_version_code (see V53's own precedent -
-- the same gap, same root cause). The new Manager 1.0.7 release was
-- created through the real, authenticated Super Admin flow with its own
-- internal identity version_code=8; with no way to set client_version_code
-- through the UI, it defaulted to 8 too (AppRelease.create's own default -
-- see domain code) instead of the real Android versionCode this build
-- actually carries, 7. This migration is that one-time, explicit,
-- reviewable correction - client_version_code -> 7 for that exact row only.
--
-- Scoped exactly like V53: application_id + channel + version_code is
-- already enforced unique by uq_app_releases_application_channel_version_code,
-- so at most one row can ever match this WHERE clause. version_name is
-- included as a second, independent identity check, purely as defense in
-- depth.
--
-- Touches nothing else: not version_code, not version_name, not any other
-- column on this row, and no other row (Manager 1.0.6, Customer, Reception,
-- or any other Manager release) can match this WHERE clause at all.
--
-- Safe on every environment, not just real production: a fresh database
-- (every test run, every new dev environment) has no such row yet, so the
-- UPDATE affects zero rows there and this migration is a no-op - exactly
-- as intended.
DO $$
DECLARE
    affected INTEGER;
BEGIN
    UPDATE app_releases
    SET client_version_code = 7
    WHERE application_id = 'ai.rojan.designlab.manager'
      AND channel = 'PRODUCTION'
      AND version_code = 8
      AND version_name = '1.0.7';

    GET DIAGNOSTICS affected = ROW_COUNT;

    IF affected > 1 THEN
        RAISE EXCEPTION
            'Expected at most 1 row to match Manager 1.0.7/versionCode 8 (application_id + channel + version_code is already unique) - found %. Refusing to apply this data correction blindly.',
            affected;
    END IF;
END $$;
