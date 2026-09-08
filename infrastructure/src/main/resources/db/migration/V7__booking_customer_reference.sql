-- BACKEND-CRM-CUSTOMER-IDENTITY-001 (Phase 1) - additive only.
--
-- Adds a nullable link from a booking to the salon's CRM record
-- (customers.id), alongside the existing bookings.customer_id -> users(id)
-- attribution column, which is deliberately left untouched in this phase.
--
-- No data is migrated here. Historical bookings keep salon_customer_id NULL
-- until the one-time backfill (see docs/migrations/V7_backfill_runbook.md)
-- is run out of band. New bookings created through the application always
-- populate it.
--
-- Reversible: see the rollback block at the bottom (kept as reference, not
-- executed by Flyway).

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS salon_customer_id UUID REFERENCES customers (id);

CREATE INDEX IF NOT EXISTS idx_bookings_salon_customer_id
    ON bookings (salon_customer_id);

-- One linked CRM record per (salon, account). Walk-in records (user_id NULL)
-- are exempt and never collide. The customers table is empty in every
-- environment at this migration's baseline, so this cannot fail on existing
-- data.
CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_salon_user
    ON customers (salon_id, user_id) WHERE user_id IS NOT NULL;

-- Rollback (reference only - not run by Flyway):
--   DROP INDEX IF EXISTS uq_customers_salon_user;
--   DROP INDEX IF EXISTS idx_bookings_salon_customer_id;
--   ALTER TABLE bookings DROP COLUMN IF EXISTS salon_customer_id;
