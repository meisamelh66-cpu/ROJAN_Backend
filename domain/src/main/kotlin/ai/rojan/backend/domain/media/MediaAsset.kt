package ai.rojan.backend.domain.media

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class MediaAssetId(val value: UUID) {
    companion object {
        fun new(): MediaAssetId = MediaAssetId(UUID.randomUUID())
    }
}

enum class MediaType { LOGO, COVER, GALLERY, PORTFOLIO, DOCUMENT }

/**
 * [PENDING] exists so a future signed-URL upload flow (client uploads
 * directly to storage, then confirms) can be introduced without changing
 * the `POST /media` endpoint shape - today's direct-multipart flow moves
 * straight to [ACTIVE], nothing currently leaves a row parked at [PENDING].
 */
enum class MediaAssetStatus {
    PENDING,
    ACTIVE,
    ARCHIVED,
    DELETED,
}

/**
 * The single home for every uploaded file this platform stores - logos,
 * covers, gallery/portfolio images, and the raw files backing
 * `SalonDocument`s (a future aggregate composes this one, never duplicates
 * it). [storageKey] is opaque to every caller above the storage adapter -
 * never a public URL, so the storage provider can change without touching
 * any row.
 */
class MediaAsset private constructor(
    val id: MediaAssetId,
    val salonId: SalonId,
    mediaType: MediaType,
    storageKey: String,
    val originalName: String,
    val mimeType: String,
    val fileSize: Long,
    status: MediaAssetStatus,
    val uploadedBy: UserId,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var mediaType: MediaType = mediaType
        private set

    var storageKey: String = storageKey
        private set

    var status: MediaAssetStatus = status
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** Marks upload confirmed and the asset servable. */
    fun activate() {
        require(status != MediaAssetStatus.DELETED) { "Cannot activate a deleted media asset" }
        status = MediaAssetStatus.ACTIVE
        touch()
    }

    /** Superseded by a newer identity-media assignment - kept, not servable as "current". */
    fun archive() {
        require(status != MediaAssetStatus.DELETED) { "Cannot archive a deleted media asset" }
        status = MediaAssetStatus.ARCHIVED
        touch()
    }

    /** Soft delete only - idempotent, deleting an already-deleted asset is a safe no-op. */
    fun delete() {
        status = MediaAssetStatus.DELETED
        touch()
    }

    private fun touch() {
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            salonId: SalonId,
            mediaType: MediaType,
            storageKey: String,
            originalName: String,
            mimeType: String,
            fileSize: Long,
            uploadedBy: UserId,
        ): MediaAsset {
            require(storageKey.isNotBlank()) { "Media asset storage key must not be blank" }
            require(originalName.isNotBlank()) { "Media asset original name must not be blank" }
            require(mimeType.isNotBlank()) { "Media asset mime type must not be blank" }
            require(fileSize > 0) { "Media asset file size must be positive" }
            val now = Instant.now()
            return MediaAsset(
                id = MediaAssetId.new(),
                salonId = salonId,
                mediaType = mediaType,
                storageKey = storageKey,
                originalName = originalName,
                mimeType = mimeType,
                fileSize = fileSize,
                status = MediaAssetStatus.ACTIVE,
                uploadedBy = uploadedBy,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: MediaAssetId,
            salonId: SalonId,
            mediaType: MediaType,
            storageKey: String,
            originalName: String,
            mimeType: String,
            fileSize: Long,
            status: MediaAssetStatus,
            uploadedBy: UserId,
            createdAt: Instant,
            updatedAt: Instant,
        ): MediaAsset = MediaAsset(
            id, salonId, mediaType, storageKey, originalName, mimeType, fileSize,
            status, uploadedBy, createdAt, updatedAt,
        )
    }
}
