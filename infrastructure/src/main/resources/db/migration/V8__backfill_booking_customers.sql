-- BACKEND-CRM-CUSTOMER-IDENTITY-001 (Phase 2) - historical data backfill.
--
-- Gives every historical (salon, booking-account) pair a linked Customer CRM
-- record, then points the booking at it via the V7 bookings.salon_customer_id
-- column. Additive, non-destructive, idempotent, salon-scoped.
--
-- Additive:      only INSERTs new customers rows and sets a column that was NULL.
-- Non-destructive: never UPDATEs an already-populated salon_customer_id, never
--                touches bookings.customer_id, never deletes anything.
-- Idempotent:    ON CONFLICT DO NOTHING on the (salon_id, user_id) unique index
--                (V7), and the UPDATE is guarded by salon_customer_id IS NULL -
--                safe to consider re-running.
-- Salon-scoped:  the join is strictly c.salon_id = b.salon_id, so a person who
--                booked at two salons gets two isolated Customer records.
--
-- PostgreSQL 16 in every environment (prod postgres:16, tests zonky 16.14) -
-- gen_random_uuid() is built in, no extension required.

-- 1. One linked Customer per (salon, user) that has bookings but no CRM record.
INSERT INTO customers (id, salon_id, user_id, full_name, phone_number, email, status, active, created_at, updated_at)
SELECT gen_random_uuid(),
       distinct_pairs.salon_id,
       u.id,
       u.full_name,
       NULL,
       u.email,
       'LEAD',
       TRUE,
       now(),
       now()
FROM (SELECT DISTINCT salon_id, customer_id FROM bookings) AS distinct_pairs
JOIN users u ON u.id = distinct_pairs.customer_id
ON CONFLICT (salon_id, user_id) WHERE user_id IS NOT NULL DO NOTHING;

-- 2. Point historical bookings at their (now guaranteed) Customer record.
UPDATE bookings b
SET salon_customer_id = c.id
FROM customers c
WHERE c.salon_id = b.salon_id
  AND c.user_id = b.customer_id
  AND b.salon_customer_id IS NULL;

-- Rollback (reference only - not run by Flyway):
--   UPDATE bookings SET salon_customer_id = NULL WHERE salon_customer_id IS NOT NULL;
--   DELETE FROM customers c
--    WHERE c.user_id IS NOT NULL
--      AND NOT EXISTS (SELECT 1 FROM customer_notes n      WHERE n.customer_id = c.id)
--      AND NOT EXISTS (SELECT 1 FROM customer_tags t       WHERE t.customer_id = c.id)
--      AND NOT EXISTS (SELECT 1 FROM customer_activities a WHERE a.customer_id = c.id);
