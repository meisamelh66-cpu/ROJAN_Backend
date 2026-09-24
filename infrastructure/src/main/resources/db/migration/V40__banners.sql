-- Banner Management (Web Phase - Super Admin). A platform-wide promotional banner, never
-- salon-/user-owned - deliberately its own table rather than a media_assets row, since that
-- table's "exactly one of salon_id/user_id" invariant has no branch for platform-owned content.
-- storage_key reuses the exact same MediaStoragePort every other upload already goes through
-- (LocalDiskMediaStorageAdapter by default in production today, S3-compatible when
-- rojan.storage.provider=s3 is set - see that adapter's own doc comment) - no second storage
-- mechanism either way.
CREATE TABLE banners
(
    id            UUID PRIMARY KEY,
    -- MANAGER is deliberately not allowed yet - a future, separate migration adds it when Manager
    -- banner support is actually built, not a flag flip on an already-permissive constraint.
    target        VARCHAR(16)  NOT NULL CHECK (target IN ('SITE', 'CUSTOMER', 'DESKTOP')),
    title         VARCHAR(255),
    subtitle      VARCHAR(500),
    href          VARCHAR(1000),
    storage_key   VARCHAR(500) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    display_order INT          NOT NULL DEFAULT 0,
    created_by    UUID         NOT NULL REFERENCES users (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every real query path (admin list-by-target, public active-by-target) filters by target first.
CREATE INDEX idx_banners_target ON banners (target);

-- The public read surface: active rows for one target, pre-sorted by display order.
CREATE INDEX idx_banners_target_active ON banners (target, is_active, display_order);
