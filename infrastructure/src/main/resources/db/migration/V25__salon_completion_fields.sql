-- Salon Completeness (Phase 1, schema only): the owner-entered profile-completion
-- fields approved for the activation-completeness gate. All nullable except the
-- internal-extensions toggle (which has a real, correct default of "off") - every
-- existing salon row is simply "not yet answered" for the rest, exactly the same
-- additive-only shape V11/V23 already established for onboarding_status/city.
-- No CHECK constraints, no application logic here - completeness is enforced in
-- the use case layer (ActivateSalonUseCase), same split this codebase already
-- uses for onboarding_status itself.
-- INTEGER, not SMALLINT: matches Hibernate's default mapping for a Kotlin
-- Int? column with no explicit type override (found during Phase 3 schema
-- validation - a SMALLINT here failed ddl-auto: validate against the real
-- entity). A year value fits in either type; INTEGER just avoids fighting the
-- ORM's own default with no functional benefit to the narrower type.
ALTER TABLE salons ADD COLUMN activity_start_jalali_year INTEGER;
ALTER TABLE salons ADD COLUMN has_internal_extensions BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE salons ADD COLUMN sells_products BOOLEAN;
ALTER TABLE salons ADD COLUMN has_cafe BOOLEAN;
ALTER TABLE salons ADD COLUMN has_staff_uniform BOOLEAN;
ALTER TABLE salons ADD COLUMN is_neighborhood_salon BOOLEAN;
ALTER TABLE salons ADD COLUMN is_city_center_salon BOOLEAN;

-- The salon's designated manager/reception contact - a pointer at *which* existing
-- salon_membership is "the" dedicated number, not a new phone column. Nullable:
-- an owner may not have invited any MANAGER/RECEPTIONIST member yet, and the
-- owner's own phone (Salon.ownerId -> users.phone_number) always remains the
-- baseline contact regardless of this field.
ALTER TABLE salons ADD COLUMN primary_contact_membership_id UUID REFERENCES salon_memberships (id);
CREATE INDEX idx_salons_primary_contact_membership_id ON salons (primary_contact_membership_id);
