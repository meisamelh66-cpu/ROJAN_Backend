-- ROJAN Verification: a read-model projection of "is this salon's latest
-- concluded verification case APPROVED" - maintained by the approve/reject
-- review use cases, never a source of truth in its own right (the real source
-- of truth stays the salon_verifications history, V20/V29). Deliberately NOT
-- referenced by onboarding_status, active, or any public-discovery query -
-- ROJAN VERIFIED never gates activation or discoverability, only decides
-- whether a badge is shown. No FK, no CHECK, no coupling of any kind to
-- onboarding_status is added here on purpose.
ALTER TABLE salons ADD COLUMN rojan_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE salons ADD COLUMN rojan_verified_at TIMESTAMPTZ;
CREATE INDEX idx_salons_rojan_verified ON salons (rojan_verified);
