package ai.rojan.backend.domain.media

import ai.rojan.backend.domain.salon.SalonId

interface MediaAssetRepository {
    fun save(mediaAsset: MediaAsset): MediaAsset
    fun findById(id: MediaAssetId): MediaAsset?
    fun findBySalonId(salonId: SalonId): List<MediaAsset>

    /** Scoped read for the public gallery endpoint - active salon's GALLERY/PORTFOLIO items only, never LOGO/COVER (those are surfaced via the salon's own resolved reference, not a list). */
    fun findBySalonIdAndMediaType(salonId: SalonId, mediaType: MediaType): List<MediaAsset>

    fun delete(id: MediaAssetId)
}
