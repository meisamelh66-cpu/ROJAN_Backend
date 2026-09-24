-- Reissue of the superseded V31 (see db/migration-superseded/README.md) -
-- Production had already advanced to V30 before V31 was ever installed, so
-- this recreates the identical schema intent as a new, in-order migration.
--
-- ROJAN Verification: extends the existing salon_documents (V19) to also carry
-- per-specialist staff hygiene certificates, reviewed individually rather than
-- as part of the overall salon verification case. specialist_id stays NULL for
-- every pre-existing, salon-level document (LICENSE/CERTIFICATE/OWNERSHIP/
-- AGREEMENT/OTHER) - only a HYGIENE_CERTIFICATE row is expected to set it.
-- reviewed_by/reviewed_at are new: SalonDocument.approve()/reject() previously
-- recorded no reviewer identity or timestamp at all (a real, pre-existing gap,
-- not introduced by this migration) - both are required by the approved design
-- ("store reviewer identity and verification timestamp/history").
ALTER TABLE salon_documents ADD COLUMN specialist_id UUID REFERENCES specialists (id);
ALTER TABLE salon_documents ADD COLUMN reviewed_by UUID REFERENCES users (id);
ALTER TABLE salon_documents ADD COLUMN reviewed_at TIMESTAMPTZ;
CREATE INDEX idx_salon_documents_specialist_id ON salon_documents (specialist_id);

-- Pre-existing width defect found during this pass, not part of the approved
-- field list but required to actually implement it: document_type was
-- VARCHAR(16) (V19), sized for the longest value that existed at the time
-- ("CERTIFICATE", 11 chars). The new DocumentType.HYGIENE_CERTIFICATE value is
-- 19 characters and would not fit - confirmed by direct count, not assumed.
-- Widened to VARCHAR(32) to match the width this project already uses for its
-- other unconstrained classifier columns (users.role, V1), with headroom for
-- future document types. A VARCHAR length increase is a metadata-only change in
-- PostgreSQL - no table rewrite, no data risk, every existing value keeps
-- fitting unchanged.
ALTER TABLE salon_documents ALTER COLUMN document_type TYPE VARCHAR(32);
