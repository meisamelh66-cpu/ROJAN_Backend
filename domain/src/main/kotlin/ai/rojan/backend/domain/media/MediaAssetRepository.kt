package ai.rojan.backend.domain.media

import ai.rojan.backend.domain.salon.SalonId

interface MediaAssetRepository {
    fun save(mediaAsset: MediaAsset): MediaAsset

    /** Tenant-scoped by construction - callers must always resolve through this, never [findById] alone, so a cross-salon lookup can 404 rather than leak. */
    fun findByIdAndSalonId(id: MediaAssetId, salonId: SalonId): MediaAsset?

    fun findBySalonId(salonId: SalonId, mediaType: MediaType? = null): List<MediaAsset>
}
