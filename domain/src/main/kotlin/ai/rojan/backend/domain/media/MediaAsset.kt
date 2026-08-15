package ai.rojan.backend.domain.media

import ai.rojan.backend.domain.salon.SalonId
import java.time.Instant
import java.util.UUID

@JvmInline
value class MediaAssetId(val value: UUID) {
    companion object {
        fun new(): MediaAssetId = MediaAssetId(UUID.randomUUID())
    }
}

/**
 * Which aggregate a [MediaAsset] belongs to. Only [SALON] is wired this
 * phase (Salon Identity Foundation) — [ownerId] is polymorphic by design
 * (the architecture this type exists for explicitly includes specialist and
 * service images), but adding those owner kinds before their own phases
 * would be exactly the kind of unrequested, speculative surface area this
 * phase was told not to build. Add a case here only when that phase
 * actually starts.
 */
enum class MediaOwnerType {
    SALON,
}

/**
 * What role a [MediaAsset] plays for its owner. Only the four salon-facing
 * types are defined — service/specialist-image types are deliberately
 * deferred with [MediaOwnerType].
 */
enum class MediaType {
    LOGO,
    COVER,
    GALLERY,
    PORTFOLIO,
}

/**
 * A single uploaded file's metadata and external-storage reference — never
 * the file's bytes (see [ai.rojan.backend.application.port.MediaStoragePort]).
 * [salonId] is the tenant boundary every access check gates on, kept
 * distinct from [ownerId]: for [MediaOwnerType.SALON] today they're numerically
 * the same value, but won't be once a specialist- or service-owned asset
 * exists — [salonId] answers "which tenant does this belong to," [ownerId]
 * answers "which specific record within that tenant."
 */
class MediaAsset private constructor(
    val id: MediaAssetId,
    val salonId: SalonId,
    val ownerType: MediaOwnerType,
    val ownerId: UUID,
    val mediaType: MediaType,
    val storageKey: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val url: String,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var updatedAt: Instant = updatedAt
        private set

    companion object {
        fun create(
            salonId: SalonId,
            ownerType: MediaOwnerType,
            ownerId: UUID,
            mediaType: MediaType,
            storageKey: String,
            fileName: String,
            mimeType: String,
            fileSize: Long,
            url: String,
        ): MediaAsset {
            require(storageKey.isNotBlank()) { "Media storage key must not be blank" }
            require(fileName.isNotBlank()) { "Media file name must not be blank" }
            require(mimeType.isNotBlank()) { "Media mime type must not be blank" }
            require(fileSize > 0) { "Media file size must be positive" }
            val now = Instant.now()
            return MediaAsset(
                id = MediaAssetId.new(),
                salonId = salonId,
                ownerType = ownerType,
                ownerId = ownerId,
                mediaType = mediaType,
                storageKey = storageKey.trim(),
                fileName = fileName.trim(),
                mimeType = mimeType.trim(),
                fileSize = fileSize,
                url = url,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: MediaAssetId,
            salonId: SalonId,
            ownerType: MediaOwnerType,
            ownerId: UUID,
            mediaType: MediaType,
            storageKey: String,
            fileName: String,
            mimeType: String,
            fileSize: Long,
            url: String,
            createdAt: Instant,
            updatedAt: Instant,
        ): MediaAsset = MediaAsset(
            id, salonId, ownerType, ownerId, mediaType, storageKey, fileName, mimeType, fileSize, url, createdAt, updatedAt,
        )
    }
}
