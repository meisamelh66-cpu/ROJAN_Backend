-- App Release Hardening: channel, publish audit and signer metadata on top of V42__app_releases.sql
-- (V42 itself is never edited). Additive only - every existing row keeps working unchanged.

-- Distribution channel, part of a release's identity together with application_id/version_code.
-- Closed set, CHECK-enforced the same way application_id is in V42. Every existing row (and every
-- caller that never names a channel) is PRODUCTION.
ALTER TABLE app_releases
    ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION'
        CHECK (channel IN ('PRODUCTION', 'BETA'));

-- Publish audit: when/by whom the release was last (re-)published. NULL until its first publish;
-- a non-NULL published_at is also what locks the artifact columns (see AppRelease.kt).
ALTER TABLE app_releases
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN published_by UUID REFERENCES users (id);

-- Signer metadata - informational only (which certificate signed the artifact). Clients must never
-- use it as their trust root. Thumbprint stored uppercase: SHA-1 (40 hex) or SHA-256 (64 hex).
ALTER TABLE app_releases
    ADD COLUMN signer_subject VARCHAR(512),
    ADD COLUMN signer_thumbprint VARCHAR(64)
        CHECK (signer_thumbprint ~ '^([0-9A-F]{40}|[0-9A-F]{64})$');

-- Optimistic lock (AppReleaseJpaEntity @Version): every save carries the version it was loaded
-- with, so a save based on an outdated copy is refused instead of overwriting newer data - e.g. a
-- stale edit landing after a concurrent publish. Existing rows start at 0.
ALTER TABLE app_releases
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Rows created before this migration were published without an audit trail - record the best
-- information that exists (last update, creator) so every PUBLISHED row satisfies the constraint
-- below. ARCHIVED rows are left alone: V42 allowed creating a row directly as ARCHIVED, so an
-- archived row is not proof it was ever published.
UPDATE app_releases
SET published_at = updated_at,
    published_by = created_by
WHERE status = 'PUBLISHED'
  AND published_at IS NULL;

ALTER TABLE app_releases
    ADD CONSTRAINT chk_app_releases_published_audit
        CHECK (status <> 'PUBLISHED' OR (published_at IS NOT NULL AND published_by IS NOT NULL));

-- Download URLs must be https. NOT VALID: enforced for every new/updated row, without failing this
-- migration on any pre-existing non-https row in an environment that already applied V42 - such a
-- row is rejected by the application on its next edit and can be fixed then.
ALTER TABLE app_releases
    ADD CONSTRAINT chk_app_releases_download_url_https
        CHECK (download_url ~* '^https://') NOT VALID;

-- versionCode uniqueness is now per application AND channel: the same build number may exist once
-- on PRODUCTION and once on BETA.
DROP INDEX uq_app_releases_application_version_code;
CREATE UNIQUE INDEX uq_app_releases_application_channel_version_code
    ON app_releases (application_id, channel, version_code);

-- The public "check for update" lookup now filters by channel too.
DROP INDEX idx_app_releases_public_lookup;
CREATE INDEX idx_app_releases_public_lookup
    ON app_releases (application_id, channel, status, is_active, version_code DESC);
