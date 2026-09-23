-- ROJAN Verification: reviewer-assessed scores on an existing salon_verifications
-- case (V20) - never owner-writable, set only when a reviewer approves a case.
-- quality_score is the approved "1 to 5" ROJAN quality score, so it gets the same
-- inline CHECK this project already uses for a bounded numeric column (see
-- services.duration_minutes/price, V2). decor_score's scale was not pinned to a
-- fixed range in the approved design, so it is left an unconstrained INTEGER
-- rather than guessing one - narrower validation can be added later without a
-- destructive change if the range is ever fixed.
--
-- INTEGER, not SMALLINT: matches Hibernate's default mapping for a Kotlin
-- Int? column with no explicit type override (found during Phase 3 schema
-- validation - SMALLINT here failed ddl-auto: validate against the real
-- entity). Neither score needs more than a single digit's range; INTEGER just
-- avoids fighting the ORM's own default with no functional benefit to the
-- narrower type.
ALTER TABLE salon_verifications ADD COLUMN quality_score INTEGER CHECK (quality_score BETWEEN 1 AND 5);
ALTER TABLE salon_verifications ADD COLUMN decor_score INTEGER;
