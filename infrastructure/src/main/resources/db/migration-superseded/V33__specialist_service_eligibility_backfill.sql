-- Specialist Eligibility (backfill only - no application-code semantics change
-- in this migration): today, isSpecialistEligibleForService() treats a
-- specialist with zero specialist_services rows (V8) as eligible for every
-- service at their own salon. A later, separate code change will remove that
-- implicit-all branch and require an explicit row for every eligible service.
-- This migration must ship and complete first, so that flip does not silently
-- make every currently-bookable specialist unbookable.
--
-- Only specialists with ZERO existing specialist_services rows are touched -
-- that is the precise, current definition of "implicitly eligible for
-- everything" (see specialist_services'/isSpecialistEligibleForService's own
-- contract). A specialist who already has one or more explicit rows is, by
-- definition, already curated and already unaffected by the implicit-all
-- branch - backfilling anything for them would silently WIDEN a real, existing,
-- deliberate assignment rather than preserve it, which the approved design
-- explicitly forbids ("preserve existing explicit assignments"). The
-- NOT EXISTS predicate below is evaluated once per specialist before any row
-- for that specialist is inserted, so this can never happen.
--
-- Every service at the same salon is covered (active and inactive alike) -
-- eligibility itself is never conditioned on a service's own active flag today
-- (that is checked separately, by the calling use case); backfilling only
-- active services would silently narrow behavior for a specialist relative to
-- a currently-inactive service that is later reactivated.
--
-- gen_random_uuid() is PostgreSQL's built-in (core, no extension needed since
-- PG 13; this project targets PG 16) UUID generator - required here because,
-- unlike every other migration in this project, this one inserts new rows
-- rather than altering existing ones, and no application code is involved to
-- supply an id.
--
-- ON CONFLICT DO NOTHING is a second, redundant safety net on top of the
-- WHERE NOT EXISTS predicate above (specialist_services' own
-- UNIQUE (specialist_id, service_id) constraint, V8, would otherwise reject a
-- genuine duplicate outright) - belt-and-suspenders, not load-bearing on its
-- own: this migration is written to insert zero duplicate rows even without it.
INSERT INTO specialist_services (id, specialist_id, service_id, created_at)
SELECT gen_random_uuid(), sp.id, sv.id, now()
FROM specialists sp
JOIN services sv ON sv.salon_id = sp.salon_id
WHERE NOT EXISTS (
    SELECT 1 FROM specialist_services ss WHERE ss.specialist_id = sp.id
)
ON CONFLICT (specialist_id, service_id) DO NOTHING;
