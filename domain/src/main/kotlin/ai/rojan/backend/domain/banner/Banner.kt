package ai.rojan.backend.domain.banner

import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class BannerId(val value: UUID) {
    companion object {
        fun new(): BannerId = BannerId(UUID.randomUUID())
    }
}

/**
 * Banner Management (Web Phase - Super Admin): the platform surface a banner is shown on.
 * MANAGER is deliberately absent - not "disabled", genuinely not a valid value yet (enforced by
 * the DB CHECK constraint in `V40__banners.sql` too, not just here) - adding it is explicit future
 * work, not a flag flip. Never salon-scoped, never user-scoped: a [Banner] belongs to the platform
 * itself, unlike every [ai.rojan.backend.domain.media.MediaAsset] row.
 */
enum class BannerTarget { SITE, CUSTOMER, DESKTOP }

/**
 * A platform-wide promotional banner shown on one [BannerTarget] surface. Deliberately its own
 * aggregate, not a [ai.rojan.backend.domain.media.MediaAsset] row: that entity's core invariant -
 * "exactly one of salonId/userId is set" - has no branch for platform-owned content, and loosening
 * it would touch the single most heavily-used table in the schema for a case that doesn't need to
 * share its lifecycle (a banner is never listed alongside a salon's gallery, never subject to
 * salon-membership permission checks). [storageKey] instead reuses the exact same
 * [ai.rojan.backend.application.port.MediaStoragePort] every other upload in this platform already
 * goes through - same storage backend (local disk by default in production today, S3-compatible
 * when explicitly configured), same URL-resolution mechanism, zero duplicate storage.
 */
class Banner private constructor(
    val id: BannerId,
    val target: BannerTarget,
    title: String?,
    subtitle: String?,
    href: String?,
    storageKey: String,
    isActive: Boolean,
    displayOrder: Int,
    val createdBy: UserId,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var title: String? = title
        private set

    var subtitle: String? = subtitle
        private set

    var href: String? = href
        private set

    var storageKey: String = storageKey
        private set

    var isActive: Boolean = isActive
        private set

    var displayOrder: Int = displayOrder
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** Metadata-only edit - never touches [storageKey]; see [replaceImage] for that. */
    fun updateMetadata(title: String?, subtitle: String?, href: String?, isActive: Boolean) {
        this.title = title?.trim()?.takeIf { it.isNotBlank() }
        this.subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() }
        this.href = href?.trim()?.takeIf { it.isNotBlank() }
        this.isActive = isActive
        touch()
    }

    /**
     * The new file is already uploaded under [newStorageKey] by the time this is called (same
     * upload-then-swap-the-reference shape [UploadMediaUseCase] uses) - the caller is responsible
     * for deleting the old [storageKey] from storage afterward, mirroring
     * [ai.rojan.backend.application.media.DeleteMediaUseCase]'s own storage-then-row ordering.
     */
    fun replaceImage(newStorageKey: String) {
        require(newStorageKey.isNotBlank()) { "Storage key must not be blank" }
        storageKey = newStorageKey
        touch()
    }

    fun reorder(newOrder: Int) {
        displayOrder = newOrder
        touch()
    }

    private fun touch() {
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            target: BannerTarget,
            title: String?,
            subtitle: String?,
            href: String?,
            storageKey: String,
            isActive: Boolean,
            displayOrder: Int,
            createdBy: UserId,
        ): Banner {
            require(storageKey.isNotBlank()) { "Storage key must not be blank" }
            val now = Instant.now()
            return Banner(
                id = BannerId.new(),
                target = target,
                title = title?.trim()?.takeIf { it.isNotBlank() },
                subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() },
                href = href?.trim()?.takeIf { it.isNotBlank() },
                storageKey = storageKey,
                isActive = isActive,
                displayOrder = displayOrder,
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: BannerId,
            target: BannerTarget,
            title: String?,
            subtitle: String?,
            href: String?,
            storageKey: String,
            isActive: Boolean,
            displayOrder: Int,
            createdBy: UserId,
            createdAt: Instant,
            updatedAt: Instant,
        ): Banner = Banner(
            id, target, title, subtitle, href, storageKey, isActive, displayOrder, createdBy, createdAt, updatedAt,
        )
    }
}
