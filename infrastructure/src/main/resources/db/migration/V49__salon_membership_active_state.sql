-- Staff Access Phase 1 (Phone -> OTP -> User -> SalonMembership -> Role -> Permissions -> Active/Disabled):
-- disabling staff access must keep the membership row (role/permissions attached) rather than
-- deleting it - DEFAULT TRUE backfills every pre-existing row as active, preserving current access.
ALTER TABLE salon_memberships ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
