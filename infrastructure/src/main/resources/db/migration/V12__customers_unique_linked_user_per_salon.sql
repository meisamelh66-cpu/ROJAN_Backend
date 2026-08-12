-- Closes the concurrent-duplicate-customer race EnsureCustomerAssociationUseCase
-- introduces (two concurrent first-time bookings for the same new customer
-- could otherwise both pass the find-check before either save completes).
-- Partial index - unlinked walk-in customers (user_id IS NULL) are legitimate
-- and unaffected, mirroring uq_customers_salon_phone's identical pattern
-- from V6__customer_crm_schema.sql.
CREATE UNIQUE INDEX uq_customers_salon_user ON customers (salon_id, user_id) WHERE user_id IS NOT NULL;
