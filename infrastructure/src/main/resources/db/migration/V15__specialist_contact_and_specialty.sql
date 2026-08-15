ALTER TABLE specialists ADD COLUMN mobile_number VARCHAR(20);
ALTER TABLE specialists ADD COLUMN specialty VARCHAR(100);

-- Nullable at the DB layer on purpose: additive-only, so the 3 specialists
-- already in production keep working with no backfill required. "Required"
-- for these fields is enforced one layer up, in CreateSpecialistRequest /
-- UpdateSpecialistRequest bean validation (api/salon/SpecialistDtos.kt) -
-- every specialist created or updated through the API from now on must
-- supply both; only pre-existing rows can still have NULLs here until an
-- owner next touches them.
