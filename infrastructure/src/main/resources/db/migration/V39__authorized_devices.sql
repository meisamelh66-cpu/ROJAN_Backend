-- Desktop Device Authorization Foundation (Phase A): the first row of the
-- User -> Salon -> AuthorizedDevice model. Windows Reception already mints
-- a deviceId/fingerprint/installationId locally (device.json, never sent
-- to the backend today) - this table is where that association actually
-- lives once it is. Foundation only: no revoke endpoint, no download
-- entitlement, no subscription/license limit is added by this migration or
-- the code shipped alongside it - deliberately out of scope for this phase.
--
-- Scoped per (user, salon, device), never per-user alone: the same physical
-- deviceId legitimately authorizes multiple independent salons (a shared
-- front-desk PC, or one owner operating two of their own salons) -
-- registering for a second salon must never touch or reassign the row
-- already registered for the first. UNIQUE (user_id, salon_id, device_id)
-- is therefore the correct idempotency key - not device_id alone (neither
-- globally unique across users nor salon-scoped by the Desktop client's own
-- design: ROJAN_Desktop's DeviceRegistrationService mints it once per
-- machine/Windows-profile with no salon awareness at all), and not
-- (user_id, device_id) either (that would forbid the legitimate multi-salon
-- case above).
--
-- The constraint is a full, unfiltered UNIQUE INDEX - deliberately not
-- partial on "revoked_at IS NULL" - so a re-registration attempt for the
-- exact same triple always collides with an existing revoked row instead of
-- inserting a second, active-looking one; the application layer is what
-- decides a revoked row stays revoked (RegisterDeviceUseCase), not the
-- schema silently allowing a bypass around it. fingerprint/installation_id
-- are excluded from the key on purpose: both are nullable, and PostgreSQL's
-- UNIQUE treats NULL as distinct from NULL, which would silently defeat
-- uniqueness for any row missing either value.
CREATE TABLE authorized_devices
(
    id              UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id),
    salon_id        UUID        NOT NULL REFERENCES salons (id),
    device_id       VARCHAR(200) NOT NULL,
    fingerprint     VARCHAR(200),
    installation_id VARCHAR(200),
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_authorized_devices_user_salon_device ON authorized_devices (user_id, salon_id, device_id);
CREATE INDEX idx_authorized_devices_user_id ON authorized_devices (user_id);
CREATE INDEX idx_authorized_devices_salon_id ON authorized_devices (salon_id);
