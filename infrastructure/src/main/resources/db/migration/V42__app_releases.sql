-- App Release Management (Super Admin Foundation): platform-owned release metadata for each real
-- ROJAN Android app, distributed directly from the ROJAN website - not a Play Store listing. See
-- `apps/website/lib/downloads/release-registry.ts` (ROJAN_Web) for the existing static file-serving
-- side this complements, not replaces: that registry still serves the actual APK bytes; this table
-- is the queryable metadata a Super Admin manages and an Android client eventually checks against
-- via the public "check for update" endpoint. application_id is a closed set of the three real
-- ROJAN apps, enforced the same way `target` is on `banners` (V40__banners.sql) - a fourth app
-- flavor needs a new migration to extend the CHECK constraint, not a flag flip on an
-- already-permissive one.
CREATE TABLE app_releases
(
    id                          UUID          PRIMARY KEY,
    application_id              VARCHAR(64)   NOT NULL
        CHECK (application_id IN ('ai.rojan.designlab.manager', 'ai.rojan.designlab', 'ai.rojan.designlab.reception')),
    version_name                VARCHAR(32)   NOT NULL,
    -- Immutable once created (see AppRelease.kt's own doc comment) - the one specific build a row
    -- identifies. Unique per app: two releases can never claim the same build number.
    version_code                INT           NOT NULL CHECK (version_code > 0),
    -- Below this versionCode, a caller of this app is always told to force-update, regardless of
    -- is_mandatory. Can never exceed this row's own version_code - a release cannot claim callers
    -- must already be newer than the release itself (enforced again in application code, since a
    -- cross-column CHECK naming another column by value is straightforward here but the app-layer
    -- invariant is the one actually relied upon - see AppRelease.create/updateMetadata).
    min_supported_version_code  INT           NOT NULL CHECK (min_supported_version_code > 0),
    is_mandatory                BOOLEAN       NOT NULL DEFAULT false,
    -- Lifecycle stage, independent of is_active - an admin can temporarily disable a PUBLISHED
    -- release (e.g. during an incident) without demoting it back to DRAFT. Only PUBLISHED+active
    -- rows are ever visible to the public latest-release lookup.
    status                      VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    download_url                VARCHAR(2000) NOT NULL,
    sha256                      VARCHAR(64)   NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    file_size_bytes             BIGINT        NOT NULL CHECK (file_size_bytes > 0),
    release_notes               TEXT,
    release_date                DATE          NOT NULL,
    is_active                   BOOLEAN       NOT NULL DEFAULT true,
    created_by                  UUID          NOT NULL REFERENCES users (id),
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Admin list-by-application view (every status, active and inactive).
CREATE INDEX idx_app_releases_application_id ON app_releases (application_id);

-- The one query the public "check for update" endpoint actually needs: the highest versionCode
-- among an application's currently published+active releases.
CREATE INDEX idx_app_releases_public_lookup ON app_releases (application_id, status, is_active, version_code DESC);

-- versionCode must be unique per app - two releases can never claim the same build number.
CREATE UNIQUE INDEX uq_app_releases_application_version_code ON app_releases (application_id, version_code);
