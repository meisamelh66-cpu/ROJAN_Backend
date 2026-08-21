package ai.rojan.backend.domain.media

import ai.rojan.backend.domain.salon.SalonId
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
}
