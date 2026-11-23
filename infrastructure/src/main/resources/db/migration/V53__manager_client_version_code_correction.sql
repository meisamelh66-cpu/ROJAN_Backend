-- Manager 1.0.6 Update-Check Data Correction: V52's own backfill correctly
-- set client_version_code = version_code for every pre-existing row - the
-- right default for every app except the one specific, already-known
-- incident it explicitly called out: ROJAN Manager 1.0.6 ships real Android
-- versionCode 6, but this table's own identity versionCode for that row is
-- 7 (6 was already claimed by an earlier, defunct row for this app+channel).
-- V52's own comment deferred this one-row correction to "a separate,
-- explicit, reviewable follow-up action" - this migration is that action,
-- chosen over a live UpdateAppRelease API call so the fix travels through
-- the same reviewed, tested, CI-gated path as everything else.
--
-- Scoped as tightly as the real data lets it be: application_id + channel +
-- version_code together are already enforced unique by
-- uq_app_releases_application_channel_version_code, so at most one row can
-- ever match this WHERE clause. version_name is included as a second,
-- independent identity check purely for defense in depth - not because the
-- schema requires it.
--
-- Touches nothing else: not version_code, not version_name, not any other
-- column on this row, and no other row (Customer, Reception, or any other
-- Manager release) can match this WHERE clause at all.
--
-- Safe on every environment, not just real production: a fresh database
-- (every test run, every new dev environment) has no such row yet, so the
-- UPDATE affects zero rows there and this migration is a no-op - exactly as
-- intended. The guard below only ever fires if more than one row matches,
-- which the unique constraint already makes impossible; it exists as a
-- documented sanity check, not a live safeguard.
DO $$
DECLARE
    affected INTEGER;
BEGIN
    UPDATE app_releases
    SET client_version_code = 6
    WHERE application_id = 'ai.rojan.designlab.manager'
      AND channel = 'PRODUCTION'
      AND version_code = 7
      AND version_name = '1.0.6';

    GET DIAGNOSTICS affected = ROW_COUNT;

    IF affected > 1 THEN
        RAISE EXCEPTION
            'Expected at most 1 row to match Manager 1.0.6/versionCode 7 (application_id + channel + version_code is already unique) - found %. Refusing to apply this data correction blindly.',
            affected;
    END IF;
END $$;
