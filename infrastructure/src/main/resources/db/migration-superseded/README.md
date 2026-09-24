# Superseded migrations — never applied to Production

The six files in this directory (V26, V27, V28, V29, V31, V33) were
authored historically as ordinary Flyway migrations, but **Production had
already advanced to V30 before any of them were ever installed**. Flyway
runs in this project with `outOfOrder` at its default (`false`) — see
`bootstrap/src/main/resources/application.yml` (`spring.flyway.enabled: true`,
no `out-of-order`/`ignore-migration-patterns` override anywhere in this
repository) — so a lower-numbered, unapplied migration appearing after a
higher-numbered one that has already run causes Flyway to abort on
`migrate()`, before Hibernate's `ddl-auto: validate` ever runs.

This directory is **not** on Flyway's scanned path
(`classpath:db/migration`, the project's only configured location — see
`application.yml`'s `spring.flyway.locations`). Files here are inert: they
are never resolved, never validated, and never applied by Flyway, no
matter what state any database is in.

The schema each of these files would have created is instead reissued,
unchanged in intent and content, as new forward migrations:

| Superseded | Reissued as | Concern |
|---|---|---|
| V26__salon_internal_extensions.sql | V34 | `salon_internal_extensions` table |
| V27__service_category_specialty_flag.sql | V35 | `service_categories.is_specialty` |
| V28__salon_geo_classification_reviews.sql | V36 | `salon_geo_classification_reviews` table |
| V29__salon_verification_review_scores.sql | V37 | `salon_verifications.quality_score` / `.decor_score` |
| V31__hygiene_certificates.sql | V38 | `salon_documents.specialist_id` / `.reviewed_by` / `.reviewed_at`, widened `document_type` |
| V33__specialist_service_eligibility_backfill.sql | *(not reissued yet)* | `specialist_services` backfill — data-only; the application code path it was preparatory for (`isSpecialistEligibleForService`'s "implicit-all-eligible" branch removal) has not shipped, so nothing in the currently deployed code requires this backfill to have run. See `V34__salon_internal_extensions.sql` and its siblings for the active migrations. |

**These files are kept here purely for historical reference** — to show
what was originally authored and why (each file's own header comment is
preserved verbatim) — not as a template to copy from without re-reading
the corresponding active V34+ migration first.

## Rule: never move these back into `db/migration/`

Moving any file in this directory back into the active,
Flyway-scanned `db/migration/` directory would immediately reproduce the
exact out-of-order failure this move was made to avoid — its version
number is still lower than V30, which is already applied in Production.
If the schema these files describe is ever fully superseded again (e.g.
V34+ is itself later reworked), author a new, higher-numbered migration
instead of resurrecting one of these.
