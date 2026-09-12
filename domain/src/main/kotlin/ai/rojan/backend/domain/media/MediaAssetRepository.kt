package ai.rojan.backend.domain.media

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.util.UUID

interface MediaAssetRepository {
    fun save(mediaAsset: MediaAsset): MediaAsset

    /** Tenant-scoped by construction - callers must always resolve through this, never [findById] alone, so a cross-salon lookup can 404 rather than leak. */
    fun findByIdAndSalonId(id: MediaAssetId, salonId: SalonId): MediaAsset?

    /**
     * [targetId] (Media System Evolution v2): an additional, optional
     * narrowing to one specialist's portfolio or one service's images -
     * `null` (the default, unchanged from before this evolution) means "no
     * target filter," not "only untargeted rows." Callers that specifically
     * want a salon's untargeted gallery (as opposed to one specialist's
     * portfolio) pass `mediaType = GALLERY` and rely on [MediaAsset.targetId]
     * being unset for every `GALLERY` row by construction (`UploadMediaUseCase`
     * never assigns a target to that type).
     */
    fun findBySalonId(salonId: SalonId, mediaType: MediaType? = null, targetId: UUID? = null): List<MediaAsset>

    /** Phase 5A.2 - the user-owned counterpart to [findByIdAndSalonId]: tenant-scoped by construction to one user, never an unscoped lookup. */
    fun findByIdAndUserId(id: MediaAssetId, userId: UserId): MediaAsset?

    /**
     * Phase 5A.2 - a user's media of one type (`AVATAR` / `PROFILE_COVER`).
     * The "replace previous safely" path uses this to find every prior
     * asset of the type before removing it, not just the one [ai.rojan.backend.domain.user.User]
     * currently references (a defensive cleanup against any past orphaned
     * row).
     */
    fun findByUserIdAndMediaType(userId: UserId, mediaType: MediaType): List<MediaAsset>

    /**
     * Genuine hard delete - never used by the salon media flow (which
     * soft-deletes via [MediaAsset.delete] + [save]). Phase 5A.2 uses this
     * exclusively: personal media is never archived and never left as a
     * queryable soft-deleted row - a user who replaces their photo expects
     * the old one actually gone.
     */
    fun delete(id: MediaAssetId)
}
