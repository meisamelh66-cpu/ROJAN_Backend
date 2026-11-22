-- Cafe Bazaar reviewer access: a narrowly-scoped, time-boxed, single-phone
-- fixed-code override, isolated from the real OTP tables entirely. This
-- migration creates only the empty table schema — no phone number, no code,
-- no reviewer-specific data of any kind. The actual grant row is written
-- later, at runtime, via the authenticated PLATFORM_ADMIN-only
-- /api/v1/platform-authority/reviewer-access/activate endpoint.
CREATE TABLE reviewer_access_grants (
    phone_number VARCHAR(32) PRIMARY KEY,
    code_hash    VARCHAR(64) NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
