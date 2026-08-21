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

/**
 * [SPECIALIST_PHOTO] (Media Sprint P0): a specialist's avatar. Deliberately
 * reuses this same salon-scoped `MediaAsset`/upload pipeline rather than a
 * parallel structure — the association to *which* specialist owns a given
 * upload is carried by `Specialist.photoUrl` (a URL, set via the existing
 * `PUT /salons/{salonId}/specialists/{specialistId}` update endpoint after
 * upload), not by a new column on this table. [targetId] (Media System
 * Evolution v2) exists for the two genuinely one-to-many cases that
 * `photoUrl`-on-the-owner can't represent: [PORTFOLIO] (many shots per
 * specialist) and [SERVICE_IMAGE] (many photos per service, new this
 * evolution) both require it and are rejected without one
 * (`UploadMediaUseCase`); [SPECIALIST_PHOTO] stays [targetId]-optional so
 * every pre-v2 upload call keeps working unchanged. Non-breaking: `media_type`
 * is a plain `VARCHAR(16)` with no DB-level enum/check constraint, so adding
 * a case needs no migration.
 */
enum class MediaType { LOGO, COVER, GALLERY, PORTFOLIO, DOCUMENT, SPECIALIST_PHOTO, SERVICE_IMAGE }

/** [PORTFOLIO]/[SERVICE_IMAGE] are meaningless without knowing *whose* portfolio or *which* service - `UploadMediaUseCase` rejects an upload of either type with no [ai.rojan.backend.domain.media.MediaAsset.targetId]. */
val TARGET_REQUIRED_MEDIA_TYPES: Set<MediaType> = setOf(MediaType.PORTFOLIO, MediaType.SERVICE_IMAGE)

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
 * covers, gallery/portfolio images, service photos, and the raw files
 * backing `SalonDocument`s (a future aggregate composes this one, never
 * duplicates it). [storageKey] is opaque to every caller above the storage
 * adapter - never a public URL, so the storage provider can change without
 * touching any row.
 *
 * [targetId] (Media System Evolution v2, nullable, defaults `null`): which
 * specialist or service this row belongs to, for [TARGET_REQUIRED_MEDIA_TYPES]
 * - a raw id, not a typed [ai.rojan.backend.domain.salon.SpecialistId]/
 * [ai.rojan.backend.domain.salon.ServiceId], since which one it is depends
 * on [mediaType] and this entity has no reason to know about either
 * aggregate. Ownership (does this id really belong to a specialist/service
 * *in this salon*) is verified in `UploadMediaUseCase`, the same layer that
 * already verifies `salonId` itself - never here, matching this entity's
 * existing "no cross-aggregate lookups" shape. `null` for every other
 * [MediaType] (salon-flat, as before this evolution).
 *
 * [displayOrder]: caller-controlled sort position within one
 * (salonId, mediaType, targetId) group, defaulting to "append at the end"
 * on upload (`UploadMediaUseCase`) and only ever changed explicitly via
 * [reorder] (`ReorderMediaUseCase`) - never implicitly reshuffled by a
 * delete or a second upload elsewhere in the group.
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
    val targetId: UUID?,
    displayOrder: Int,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var mediaType: MediaType = mediaType
        private set

    var storageKey: String = storageKey
        private set

    var status: MediaAssetStatus = status
        private set

    var displayOrder: Int = displayOrder
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

    /** [ReorderMediaUseCase]'s single mutation - a plain position within whatever group [targetId]/[mediaType] already place this row in, never validated here (group membership can't change via reorder, only position within it). */
    fun reorder(newOrder: Int) {
        displayOrder = newOrder
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
            targetId: UUID? = null,
            displayOrder: Int = 0,
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
                targetId = targetId,
                displayOrder = displayOrder,
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
            targetId: UUID? = null,
            displayOrder: Int = 0,
        ): MediaAsset = MediaAsset(
            id, salonId, mediaType, storageKey, originalName, mimeType, fileSize,
            status, uploadedBy, targetId, displayOrder, createdAt, updatedAt,
        )
    }
}
