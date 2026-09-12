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
 *
 * [AVATAR] / [PROFILE_COVER] (Phase 5A.2, User Profile Media): a *user's*
 * own avatar/cover, not a salon's. Unlike every other case above, these are
 * never salon-owned - see [MediaAsset.userId].
 */
enum class MediaType { LOGO, COVER, GALLERY, PORTFOLIO, DOCUMENT, SPECIALIST_PHOTO, SERVICE_IMAGE, AVATAR, PROFILE_COVER }

/** [AVATAR] / [PROFILE_COVER] are the only user-owned media types - a user has exactly one of each. Phase 5A.2. */
val USER_MEDIA_TYPES: Set<MediaType> = setOf(MediaType.AVATAR, MediaType.PROFILE_COVER)

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
 * covers, gallery/portfolio images, service photos, user avatars/covers,
 * and the raw files backing `SalonDocument`s (a future aggregate composes
 * this one, never duplicates it). [storageKey] is opaque to every caller
 * above the storage adapter - never a public URL, so the storage provider
 * can change without touching any row.
 *
 * [salonId] / [userId] (Phase 5A.2): exactly one is non-null - every
 * pre-5A.2 row is salon-owned ([salonId] set, [userId] null); [AVATAR]/
 * [PROFILE_COVER] rows are user-owned instead ([salonId] null, [userId]
 * set). This is a schema extension of the *same* aggregate, not a parallel
 * table - matching this class's own stated principle above.
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
 * [MediaType] (salon-flat, as before this evolution) - including user-owned
 * rows, which have no notion of a target at all.
 *
 * [displayOrder]: caller-controlled sort position within one
 * (salonId, mediaType, targetId) group, defaulting to "append at the end"
 * on upload (`UploadMediaUseCase`) and only ever changed explicitly via
 * [reorder] (`ReorderMediaUseCase`) - never implicitly reshuffled by a
 * delete or a second upload elsewhere in the group. User-owned rows never
 * reorder (a user has exactly one avatar and one cover); stays `0`.
 */
class MediaAsset private constructor(
    val id: MediaAssetId,
    val salonId: SalonId?,
    val userId: UserId?,
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

    init {
        require((salonId != null) != (userId != null)) {
            "A media asset must have exactly one of salonId/userId set"
        }
        if (userId != null) {
            require(uploadedBy == userId) { "User-owned media must be uploaded by its own owner - no delegated upload" }
        }
    }

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
        /** Salon-owned media. Unchanged call shape from before Phase 5A.2 - every existing caller keeps compiling untouched. */
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
            require(mediaType !in USER_MEDIA_TYPES) { "$mediaType is user-owned media; use createForUser" }
            require(storageKey.isNotBlank()) { "Media asset storage key must not be blank" }
            require(originalName.isNotBlank()) { "Media asset original name must not be blank" }
            require(mimeType.isNotBlank()) { "Media asset mime type must not be blank" }
            require(fileSize > 0) { "Media asset file size must be positive" }
            val now = Instant.now()
            return MediaAsset(
                id = MediaAssetId.new(),
                salonId = salonId,
                userId = null,
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

        /**
         * User-owned profile media (avatar / profile cover). Phase 5A.2.
         * The owner is always the acting user - `uploadedBy` is always
         * [userId], there is no separate caller-supplied uploader; no
         * [targetId] (meaningless for a user), no [displayOrder] (a user
         * has exactly one of each type, nothing to reorder).
         */
        fun createForUser(
            userId: UserId,
            mediaType: MediaType,
            storageKey: String,
            originalName: String,
            mimeType: String,
            fileSize: Long,
        ): MediaAsset {
            require(mediaType in USER_MEDIA_TYPES) { "User media must be AVATAR or PROFILE_COVER, was $mediaType" }
            require(storageKey.isNotBlank()) { "Media asset storage key must not be blank" }
            require(originalName.isNotBlank()) { "Media asset original name must not be blank" }
            require(mimeType.isNotBlank()) { "Media asset mime type must not be blank" }
            require(fileSize > 0) { "Media asset file size must be positive" }
            val now = Instant.now()
            return MediaAsset(
                id = MediaAssetId.new(),
                salonId = null,
                userId = userId,
                mediaType = mediaType,
                storageKey = storageKey,
                originalName = originalName,
                mimeType = mimeType,
                fileSize = fileSize,
                status = MediaAssetStatus.ACTIVE,
                uploadedBy = userId,
                targetId = null,
                displayOrder = 0,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: MediaAssetId,
            salonId: SalonId?,
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
            userId: UserId? = null,
        ): MediaAsset = MediaAsset(
            id, salonId, userId, mediaType, storageKey, originalName, mimeType, fileSize,
            status, uploadedBy, targetId, displayOrder, createdAt, updatedAt,
        )
    }
}
