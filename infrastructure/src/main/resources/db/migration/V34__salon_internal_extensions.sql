-- Reissue of the superseded V26 (see db/migration-superseded/README.md) -
-- Production had already advanced to V30 before V26 was ever installed, so
-- this recreates the identical schema intent as a new, in-order migration.
--
-- Salon Completeness: a salon's internal telephone extensions - only meaningful
-- when salons.has_internal_extensions (V25) is true, but rows are not deleted if
-- an owner later toggles it off (the toggle is presentational; history is kept).
-- title_type/title are two separate columns per the approved design: title_type
-- is one of RECEPTION/ACCOUNTING/MANAGEMENT/RESERVATION/CUSTOM (validated in the
-- application layer only - plain VARCHAR, no DB CHECK/ENUM, matching this
-- project's existing convention for every other classifier column - see
-- salon_documents.document_type (V19) and salon_verifications.status (V20),
-- neither of which has a DB-level restriction either). title is meaningful only
-- for CUSTOM (the owner's own free-text label); a standard title_type leaves it
-- NULL - not enforced here, same "required one layer up" split V15 already used
-- for specialists.mobile_number/specialty.
CREATE TABLE salon_internal_extensions
(
    id               UUID        PRIMARY KEY,
    salon_id         UUID        NOT NULL REFERENCES salons (id),
    title_type       VARCHAR(20) NOT NULL,
    title            VARCHAR(120),
    extension_number VARCHAR(16) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_salon_internal_extensions_salon_id ON salon_internal_extensions (salon_id);
