ALTER TABLE salons ADD COLUMN onboarding_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE salons ADD COLUMN logo_url VARCHAR(1000);
ALTER TABLE salons ADD COLUMN latitude DOUBLE PRECISION;
ALTER TABLE salons ADD COLUMN longitude DOUBLE PRECISION;

-- The DEFAULT above backfills every existing (already-operating) production
-- salon to ACTIVE in the same statement that adds the column. Dropping the
-- default afterward means every future insert must state its status
-- explicitly - the application layer already does, via Salon.create's
-- onboardingStatus parameter.
ALTER TABLE salons ALTER COLUMN onboarding_status DROP DEFAULT;
