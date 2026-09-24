package ai.rojan.backend.application.banner

import ai.rojan.backend.application.media.ImageContentSniffer
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.banner.Banner
import ai.rojan.backend.domain.banner.BannerId
import ai.rojan.backend.domain.banner.BannerRepository
import ai.rojan.backend.domain.banner.BannerTarget
import ai.rojan.backend.domain.common.BannerNotFoundException
import ai.rojan.backend.domain.common.BannerReorderMismatchException
import ai.rojan.backend.domain.common.MediaSizeExceededException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.user.UserId
import java.util.UUID

private val ALLOWED_BANNER_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
private const val MAX_BANNER_BYTES = 8L * 1024 * 1024

/**
 * Validates and stores banner image bytes under a fresh `banners/{target}/{uuid}.{ext}` storage
 * key - same [ai.rojan.backend.application.media.ImageContentSniffer] magic-byte detection
 * [ai.rojan.backend.application.media.UploadMediaUseCase] already uses (same module, `internal`
 * visibility - not a second sniffer), same reasoning: the declared Content-Type is client-supplied
 * and independently spoofable, only the real leading bytes decide what an image really is.
 */
private fun storeBannerImage(mediaStoragePort: MediaStoragePort, target: BannerTarget, content: ByteArray, mimeType: String): String {
    if (mimeType !in ALLOWED_BANNER_MIME_TYPES) {
        throw MediaTypeInvalidException(mimeType, "BANNER")
    }
    val actualBytes = content.size.toLong()
    if (actualBytes > MAX_BANNER_BYTES) {
        throw MediaSizeExceededException(actualBytes, MAX_BANNER_BYTES)
    }
    val detected = ImageContentSniffer.detect(content)
    if (detected == null || detected != mimeType) {
        throw MediaTypeInvalidException(mimeType, "BANNER")
    }
    val extension = ImageContentSniffer.EXTENSIONS_BY_MIME_TYPE.getValue(detected)
    val storageKey = "banners/${target.name.lowercase()}/${UUID.randomUUID()}.$extension"
    mediaStoragePort.upload(storageKey, content, mimeType)
    return storageKey
}

data class UploadBannerCommand(
    val callerId: UserId,
    val target: BannerTarget,
    val title: String?,
    val subtitle: String?,
    val href: String?,
    val isActive: Boolean,
    val content: ByteArray,
    val mimeType: String,
)

/** PLATFORM_ADMIN only. Appends at the end of the target's own display-order group - a new banner never reshuffles existing ones. */
class UploadBannerUseCase(
    private val bannerRepository: BannerRepository,
    private val mediaStoragePort: MediaStoragePort,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: UploadBannerCommand): Banner {
        platformAuthorization.requirePlatformAdmin(command.callerId)

        val storageKey = storeBannerImage(mediaStoragePort, command.target, command.content, command.mimeType)
        val nextOrder = bannerRepository.findByTarget(command.target).maxOfOrNull { it.displayOrder }?.plus(1) ?: 0

        val banner = Banner.create(
            target = command.target,
            title = command.title,
            subtitle = command.subtitle,
            href = command.href,
            storageKey = storageKey,
            isActive = command.isActive,
            displayOrder = nextOrder,
            createdBy = command.callerId,
        )
        return bannerRepository.save(banner)
    }
}

data class ListBannersForAdminQuery(val callerId: UserId, val target: BannerTarget)

/** PLATFORM_ADMIN only - the admin management view, includes inactive banners. */
class ListBannersForAdminUseCase(
    private val bannerRepository: BannerRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListBannersForAdminQuery): List<Banner> {
        platformAuthorization.requirePlatformAdmin(query.callerId)
        return bannerRepository.findByTarget(query.target)
    }
}

data class ListActiveBannersQuery(val target: BannerTarget)

/**
 * No authorization at all, by design - this is the public surface every client (the marketing
 * website, and eventually the Customer/Desktop apps) reads banners from. Mirrors
 * [ai.rojan.backend.application.media.ListMediaUseCase]'s own "intentionally open" shape.
 */
class ListActiveBannersUseCase(
    private val bannerRepository: BannerRepository,
) {
    fun execute(query: ListActiveBannersQuery): List<Banner> = bannerRepository.findActiveByTarget(query.target)
}

data class UpdateBannerMetadataCommand(
    val callerId: UserId,
    val bannerId: BannerId,
    val title: String?,
    val subtitle: String?,
    val href: String?,
    val isActive: Boolean,
)

/** PLATFORM_ADMIN only. Never touches the image - see [ReplaceBannerImageUseCase] for that. */
class UpdateBannerMetadataUseCase(
    private val bannerRepository: BannerRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: UpdateBannerMetadataCommand): Banner {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val banner = bannerRepository.findById(command.bannerId) ?: throw BannerNotFoundException(command.bannerId.value.toString())
        banner.updateMetadata(command.title, command.subtitle, command.href, command.isActive)
        return bannerRepository.save(banner)
    }
}

data class ReplaceBannerImageCommand(
    val callerId: UserId,
    val bannerId: BannerId,
    val content: ByteArray,
    val mimeType: String,
)

/**
 * PLATFORM_ADMIN only. Upload-then-swap-then-delete-old ordering (same shape
 * [ai.rojan.backend.application.media.DeleteMediaUseCase] uses for its own storage/row ordering):
 * the new file is durably stored and the row is updated to point at it *before* the old file is
 * removed - a failure between those two steps leaves an orphaned old file, never a banner pointing
 * at nothing.
 */
class ReplaceBannerImageUseCase(
    private val bannerRepository: BannerRepository,
    private val mediaStoragePort: MediaStoragePort,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ReplaceBannerImageCommand): Banner {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val banner = bannerRepository.findById(command.bannerId) ?: throw BannerNotFoundException(command.bannerId.value.toString())

        val oldStorageKey = banner.storageKey
        val newStorageKey = storeBannerImage(mediaStoragePort, banner.target, command.content, command.mimeType)
        banner.replaceImage(newStorageKey)
        val saved = bannerRepository.save(banner)
        mediaStoragePort.delete(oldStorageKey)
        return saved
    }
}

data class DeleteBannerCommand(val callerId: UserId, val bannerId: BannerId)

/** PLATFORM_ADMIN only. Explicit, single-banner action - never automatic, never cascaded from anything else. */
class DeleteBannerUseCase(
    private val bannerRepository: BannerRepository,
    private val mediaStoragePort: MediaStoragePort,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: DeleteBannerCommand) {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val banner = bannerRepository.findById(command.bannerId) ?: throw BannerNotFoundException(command.bannerId.value.toString())
        bannerRepository.delete(banner.id)
        mediaStoragePort.delete(banner.storageKey)
    }
}

data class ReorderBannersCommand(val callerId: UserId, val target: BannerTarget, val orderedBannerIds: List<BannerId>)

/** PLATFORM_ADMIN only. Every id must already belong to this exact target's group - a stray id fails the whole call, same discipline as [ai.rojan.backend.application.media.ReorderMediaUseCase]. */
class ReorderBannersUseCase(
    private val bannerRepository: BannerRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ReorderBannersCommand) {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val group = bannerRepository.findByTarget(command.target).associateBy { it.id }

        val banners = command.orderedBannerIds.map { id ->
            group[id] ?: throw BannerReorderMismatchException(id.value.toString())
        }

        banners.forEachIndexed { index, banner ->
            banner.reorder(index)
            bannerRepository.save(banner)
        }
    }
}
