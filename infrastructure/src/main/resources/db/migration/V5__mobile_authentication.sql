-- Mobile-First Authentication Phase 1: email/password is no longer the only
-- account identity. email and password_hash become optional; phone_number
-- is added as an equally-valid identity anchor. The CHECK constraint below
-- mirrors domain.user.User's own "email or phone required" invariant at the
-- database level (defense in depth — the domain layer is authoritative,
-- this is a backstop against a bug or a direct SQL write bypassing it).

ALTER TABLE users
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD COLUMN phone_number VARCHAR(20);

-- Partial unique index: only enforces uniqueness among non-null phone
-- numbers, so any number of accounts may have phone_number = NULL (every
-- existing email/password account, today).
CREATE UNIQUE INDEX uq_users_phone_number ON users (phone_number) WHERE phone_number IS NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT chk_users_identity CHECK (email IS NOT NULL OR phone_number IS NOT NULL);
