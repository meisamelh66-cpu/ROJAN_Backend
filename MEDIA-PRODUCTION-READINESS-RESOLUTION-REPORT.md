# Media System Production Readiness — Blocker Resolution Report

**Date:** 2026-09-11
**Branch:** `release/production-v24` (built directly on the real production lineage — `origin/release/v1.2.0-manager-dashboard-rbac-fix` + the booking-discovery fix, confirmed `V1`–`V23` matches production's real, VPS-verified migration chain)
**Status:** ✅ Code and release package are production-safe. **Not deployed.** Nothing committed — per instruction, only the working tree and a built artifact exist.

---

## What changed

Phase 5A.2 (User Profile Media — avatar/cover upload) implemented **fresh, directly against the real canonical Media System v2 foundation**, resolving the V18/V19 migration-numbering collision that blocked the previous implementation attempt (see `FINAL-MIGRATION-RELEASE-GATE.md`) by building on the correct base from the start rather than trying to reconcile an incompatible one after the fact.

### Domain (`domain/`)
- **`media/MediaAsset.kt`**: `salonId` made nullable, new `userId: UserId?` added — exactly one of the two is set, enforced by a new `init {}` invariant (mirrors the same "exactly one owner" pattern already proven in this codebase's `V5__mobile_authentication.sql` email/phone CHECK). New `MediaType.AVATAR`/`PROFILE_COVER`. New `MediaAsset.createForUser(...)` factory — self-upload only (`uploadedBy` is always `userId`, no separate caller-supplied uploader). `targetId`/`displayOrder` (the existing Media System Evolution v2 columns) are untouched and simply stay `null`/`0` for user-owned rows — no new column needed for either.
- **`media/MediaAssetRepository.kt`**: two new methods, purely additive — `findByIdAndUserId` (mirrors the existing `findByIdAndSalonId` tenant-scoping pattern) and `findByUserIdAndMediaType`, plus a genuine hard `delete(id)`. Every existing method (`save`, `findByIdAndSalonId`, `findBySalonId`) is byte-for-byte unchanged.
- **`user/User.kt`**: new `avatarMediaId`/`coverMediaId` slots + `assignAvatarMedia`/`assignCoverMedia`, mirroring `Salon.assignIdentityMedia`'s existing pattern exactly.

### Infrastructure (`infrastructure/`)
- **`MediaAssetJpaEntity.kt`**: `salon_id` column made nullable, new `user_id` column.
- **`MediaAssetRepositoryAdapter.kt`** / **`MediaAssetSpringDataRepository.kt`**: nullable-owner mapping, the two new repository methods wired to real Spring Data derived queries.
- **`UserJpaEntity.kt`** / **`UserRepositoryAdapter.kt`**: new `avatar_media_id`/`cover_media_id` columns and mapping.

### Database — new migration
- **`V24__user_profile_media.sql`** (numbered correctly past production's confirmed real ceiling, `V23`): `ALTER COLUMN salon_id DROP NOT NULL`, `ADD COLUMN user_id` + CHECK constraint, 2 new indexes, `users.avatar_media_id`/`cover_media_id`. **Purely additive — see "Migration impact" below.**

### Application (`application/media/`)
- **`MediaUseCases.kt`**: one, minimal, deliberate visibility change — `private object ImageContentSniffer` → `internal object ImageContentSniffer`. Nothing else in this file was touched; every existing salon use case (`UploadMediaUseCase`/`ListMediaUseCase`/`ReorderMediaUseCase`/`DeleteMediaUseCase`/`AssignIdentityMediaUseCase`) is byte-for-byte unchanged.
- **`UserProfileMediaUseCases.kt`** (new): `UploadUserAvatarUseCase`, `UploadUserCoverUseCase`, `DeleteUserAvatarUseCase`, `DeleteUserCoverUseCase`. Reuses the now-`internal` `ImageContentSniffer` for magic-byte validation — not a second implementation.

### API (`api/`)
- **`UserController.kt`**: four new endpoints (`POST`/`DELETE /api/v1/users/me/media/{avatar,cover}`), each resolving the caller exclusively from the JWT principal (`CurrentUserResolver`) — no path/body parameter ever names a user id.
- **`AuthDtos.kt`**: `UserResponse` gains `avatarUrl`/`coverUrl` — both nullable with a default of `null`, so `AuthController`'s existing `UserResponse(...)` construction (which doesn't set them) keeps compiling and behaving unchanged.
- **`MediaController.kt`**: one forced, one-line fix — `salonId.value` → `requireNotNull(salonId) { ... }.value` in `toResponse()`, since `salonId` is now nullable at the type level (every asset this controller ever handles is still salon-owned in practice; this just makes that assumption explicit and safe rather than silently relying on non-null).
- **`MediaUseCaseConfig.kt`**: four new `@Bean` definitions for the new use cases. No new configuration properties — the new use cases hardcode the same `8 MB` / `image/png,jpeg,webp` policy already hardcoded in `MediaUseCases.kt` for salon media, matching this branch's own established style (config-driven limits were a different branch's approach, not this one's).

---

## Why this approach was chosen

**The root problem was never the code — it was the base.** A previous implementation attempt built Media M0 + Phase 5A.2 on a branch descended from `ec2681b` in isolation, which had never received the real, already-shipped-to-production "Media System Evolution v2" (specialist photos, service images, reorder, `target_id`/`display_order` — production's real `V18`–`V22`). That mismatch produced two irreconcilable version-number collisions (this branch's own `V18`/`V19` vs. production's real, different-content `V18`/`V19`), fully documented in `FINAL-MIGRATION-RELEASE-GATE.md`.

**This session started from a branch (`release/production-v24`) already correctly rebuilt on the real production lineage** — confirmed via direct inspection: `V1`–`V23` present, matching exactly the VPS-verified migration chain you provided (`V18` = reconcile, `V19` = salon_documents, `V20` = salon_verifications, `V21` = audit_events, `V22` = media_asset_target_and_order, `V23` = salon_city), with the booking-discovery fix already applied on top. Given this, **the only correct move was to build Phase 5A.2 directly against this real foundation, numbered `V24`** — not to renumber a mismatched branch's files after the fact, and not to invent a reconciliation migration for a divergence that no longer exists once building on the right base. Options 2 ("renumber to V24+") and 3 ("reconciliation migration") from the task's own framing collapse into the same, simplest answer once the base is correct: **a fresh, correctly-numbered, purely additive migration** — which is what `V24__user_profile_media.sql` is.

No domain rule was weakened to get here. `Salon.requireActivated()`, the salon media permission model (`Permission.MANAGE_MEDIA`), and every existing salon media invariant are completely untouched.

---

## Migration impact

| Requirement | How it's satisfied |
|---|---|
| Preserve all existing production data | `V24` contains zero `DROP`/`UPDATE`/data-transforming statements. Every existing salon-owned row already satisfies the new `chk_media_assets_exactly_one_owner` CHECK by construction (`salon_id IS NOT NULL AND user_id IS NULL` is exactly its current state) — no backfill needed or performed. |
| No destructive DROP TABLE or destructive migration | Confirmed: `V24`'s only structural changes are `ALTER COLUMN ... DROP NOT NULL` (a relaxation, not a removal), two `ADD COLUMN` pairs, one `ADD CONSTRAINT`, two `CREATE INDEX`. Nothing is dropped. |
| Flyway checksum integrity | `V24` is a **new** version number — it cannot collide with anything already in `flyway_schema_history`, unlike the earlier V18/V19 situation. `V1`–`V23` are untouched, byte-for-byte, on this branch — their recorded checksums remain valid. |
| Existing salon media continues working | Confirmed by test, not just by inspection: `MediaUseCasesTest` (the full existing salon media suite — upload/list/reorder/delete/assign-identity, 31 tests) passes unchanged, plus a dedicated regression test in the new integration suite that uploads a user avatar *and* runs the full salon-media create/list flow in the same test. |
| User Profile Media deployable after this | Confirmed by test: the new `/users/me/media/{avatar,cover}` endpoints work end-to-end against a real embedded Postgres, including the migration itself actually running (Flyway applied `V1`→`V24` fresh in every integration test run this session — this isn't a paper analysis, `V24` was exercised for real, repeatedly). |

**Numbering:** `V24` is the correct next version given the confirmed real ceiling (`V23`). If the real production database's ceiling has moved past `V23` by the time this actually deploys, **re-verify before deploying** — this migration's number is only correct as of the ceiling you reported this session.

---

## Test results

```
Baseline (before any change, this branch as received): 588/588 tests, 0 failures
After Phase 5A.2 implementation:                        608/608 tests, 0 failures
                                                          (+20: 13 new unit + 7 new integration)
```

Breakdown of the new/affected tests:
- `MediaUseCasesTest` (existing salon media, untouched): **31/31**, confirmed zero regression from the domain-layer nullable-owner change.
- `UserProfileMediaUseCasesTest` (new, 13 tests): avatar/cover upload success, hard-delete-on-replace (never archived), unknown-caller rejection, mime/size/content-sniffing rejection, delete + idempotent re-delete, cross-slot independence (deleting cover leaves avatar untouched).
- `UserProfileMediaFlowIntegrationTest` (new, 7 tests, real HTTP + real embedded Postgres): full upload→`GET /me`-resolves-URL round trip, cover independence, cross-account isolation, delete + idempotency, re-upload replacing the URL, unauthenticated rejection (401), and the explicit salon-media regression check described above.

`./gradlew test` (full suite, every module) run twice this session — once as the pre-change baseline, once as the final post-change confirmation — both fully green.

---

## Remaining risks

1. **Production ceiling drift.** This report's entire numbering decision (`V24`) rests on the `V1`→`V23` chain you confirmed this session. If anything has migrated production further since that check, this file's version number needs re-verification before deployment — re-run the `flyway_schema_history` query first.
2. **Storage backend not independently re-verified this session.** This branch supports both `LocalDiskMediaStorageAdapter` and `S3CompatibleMediaStorage` behind the same `MediaStoragePort` interface — the new user-media use cases are storage-backend-agnostic by construction (same port, no new adapter code), but which one production actually has configured (and whether its bucket/disk policy needs any change for a new `users/{userId}/media/...` key prefix alongside the existing `salons/{salonId}/media/...` one) was not re-confirmed live this session.
3. **EXIF/GPS metadata is not stripped server-side** for avatar/cover uploads, same as every other image type on this platform already — a pre-existing, shared gap, not introduced or worsened here. The Android client already strips this before upload; this is not defense-in-depth against a direct API caller.
4. **Deployment sequencing is out of this report's scope.** This report makes the code and release artifact (`bootstrap-1.0.0.jar`, includes `V24` packaged correctly, confirmed by direct inspection of the built jar) production-safe. It does not deploy anything — per instruction, that remains a separate, deliberate step for whoever has VPS access, following `BACKEND-PRODUCTION-DEPLOY-CHECKLIST.md`'s existing procedure (backup → confirm ceiling one more time → migrate → restart → verify health/auth/booking/media).

---

**Build artifact:** `bootstrap/build/libs/bootstrap-1.0.0.jar` (~104 MB, valid, `V24` migration confirmed packaged inside the nested `infrastructure-1.0.0.jar` at the correct position after `V23`). **Not deployed. Nothing committed.**
