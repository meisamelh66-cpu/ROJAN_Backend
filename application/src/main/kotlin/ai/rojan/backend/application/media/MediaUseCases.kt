package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.MediaSizeExceededException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.common.MediaTypeMismatchException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaAssetStatus
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import java.util.UUID

private val PUBLIC_IMAGE_TYPES = setOf(MediaType.LOGO, MediaType.COVER, MediaType.GALLERY, MediaType.PORTFOLIO)
private val ALLOWED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
// Document Archive (Phase 2) Security Gate §10.3: a license/certificate is
// as often a phone-camera photo as a scanned PDF - PDF-only would reject
// the common case.
private val ALLOWED_DOCUMENT_MIME_TYPES = setOf("application/pdf", "image/jpeg", "image/png")
private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
private const val MAX_DOCUMENT_BYTES = 20L * 1024 * 1024

data class UploadMediaCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val mediaType: MediaType,
    val content: ByteArray,
    val originalName: String,
    val mimeType: String,
)

/**
 * Mime allow-list and size ceilings are application-layer policy, not a
 * [MediaAsset] domain invariant - they're configuration-shaped rules about
 * what this platform currently accepts, not a structural truth about what
 * a media asset *is* (unlike, say, a blank storage key).
 */
class UploadMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(command: UploadMediaCommand): MediaAsset {
        salonRepository.findById(command.salonId) ?: throw SalonNotFoundException(command.salonId.value.toString())
        // Document Archive (Phase 2): DOCUMENT uploads require MANAGE_DOCUMENTS,
        // never the weaker MANAGE_MEDIA - closes the gap where a Manager
        // (MANAGE_MEDIA but not MANAGE_DOCUMENTS, per the approved matrix)
        // could otherwise upload compliance documents.
        val requiredPermission = if (command.mediaType == MediaType.DOCUMENT) Permission.MANAGE_DOCUMENTS else Permission.MANAGE_MEDIA
        salonPermissionResolver.require(command.salonId, command.callerId, requiredPermission)

        val isPublicImage = command.mediaType in PUBLIC_IMAGE_TYPES
        // Every media type is now mime-validated - DOCUMENT previously had
        // no check at all (Security Gate §10.3 finding).
        val allowedMimeTypes = if (isPublicImage) ALLOWED_IMAGE_MIME_TYPES else ALLOWED_DOCUMENT_MIME_TYPES
        if (command.mimeType !in allowedMimeTypes) {
            throw MediaTypeInvalidException(command.mimeType, command.mediaType.name)
        }
        val maxBytes = if (isPublicImage) MAX_IMAGE_BYTES else MAX_DOCUMENT_BYTES
        val actualBytes = command.content.size.toLong()
        if (actualBytes > maxBytes) {
            throw MediaSizeExceededException(actualBytes, maxBytes)
        }

        // Document Archive (Phase 2) Security Gate §10.1: DOCUMENT content
        // lives under a distinct, private-only prefix - never the public
        // media/ prefix, so a bucket policy can grant public read on
        // media/ while denying it entirely on documents/.
        val prefix = if (command.mediaType == MediaType.DOCUMENT) "documents" else "media"
        val storageKey = "salons/${command.salonId.value}/$prefix/${UUID.randomUUID()}"
        mediaStoragePort.upload(storageKey, command.content, command.mimeType)

        val mediaAsset = MediaAsset.create(
            salonId = command.salonId,
            mediaType = command.mediaType,
            storageKey = storageKey,
            originalName = command.originalName,
            mimeType = command.mimeType,
            fileSize = actualBytes,
            uploadedBy = command.callerId,
        )
        return mediaAssetRepository.save(mediaAsset)
    }
}

data class ListMediaQuery(
    val salonId: SalonId,
    val mediaType: MediaType?,
)

class ListMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
) {
    /**
     * No permission gate beyond a valid salon - this is the *public* media
     * surface (logo/cover/gallery/portfolio), intentionally open to any
     * authenticated caller. Document Archive (Phase 2) Security Gate
     * §10.1: `DOCUMENT`-typed assets are unconditionally excluded here,
     * regardless of any `mediaType` filter the caller passes - documents
     * are discoverable exclusively through the permission-gated
     * `GET /salons/{salonId}/documents` surface, never this one.
     */
    fun execute(query: ListMediaQuery): List<MediaAsset> {
        salonRepository.findById(query.salonId) ?: throw SalonNotFoundException(query.salonId.value.toString())
        return mediaAssetRepository.findBySalonId(query.salonId, query.mediaType)
            .filter { it.status != MediaAssetStatus.DELETED && it.mediaType != MediaType.DOCUMENT }
    }
}

data class DeleteMediaCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val mediaId: MediaAssetId,
)

/** Clearing a live identity-slot assignment happens in the same use case as the delete - never left dangling, never a second call the caller could forget. */
class DeleteMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(command: DeleteMediaCommand) {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(command.salonId, command.callerId, Permission.MANAGE_MEDIA)
        val mediaAsset = mediaAssetRepository.findByIdAndSalonId(command.mediaId, command.salonId)
            ?: throw MediaAssetNotFoundException(command.mediaId.value.toString())

        mediaAsset.delete()
        mediaAssetRepository.save(mediaAsset)
        mediaStoragePort.delete(mediaAsset.storageKey)

        var salonChanged = false
        if (salon.logoMediaId == mediaAsset.id) {
            salon.assignIdentityMedia(IdentitySlot.LOGO, null)
            salonChanged = true
        }
        if (salon.coverMediaId == mediaAsset.id) {
            salon.assignIdentityMedia(IdentitySlot.COVER, null)
            salonChanged = true
        }
        if (salonChanged) {
            salonRepository.save(salon)
        }
    }
}

data class AssignIdentityMediaCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val slot: IdentitySlot,
    val mediaId: MediaAssetId?,
)

/** The previously-assigned asset (if any) is archived, not deleted - recoverable, and preserves the audit trail of what a salon's identity looked like over time. */
class AssignIdentityMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: AssignIdentityMediaCommand): Salon {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(command.salonId, command.callerId, Permission.MANAGE_MEDIA)

        val previousMediaId = when (command.slot) {
            IdentitySlot.LOGO -> salon.logoMediaId
            IdentitySlot.COVER -> salon.coverMediaId
        }

        if (command.mediaId != null) {
            val mediaAsset = mediaAssetRepository.findByIdAndSalonId(command.mediaId, command.salonId)
                ?: throw MediaAssetNotFoundException(command.mediaId.value.toString())
            val expectedType = if (command.slot == IdentitySlot.LOGO) MediaType.LOGO else MediaType.COVER
            if (mediaAsset.mediaType != expectedType) {
                throw MediaTypeMismatchException(mediaAsset.id.value.toString(), expectedType.name)
            }
        }

        salon.assignIdentityMedia(command.slot, command.mediaId)
        val saved = salonRepository.save(salon)

        if (previousMediaId != null && previousMediaId != command.mediaId) {
            mediaAssetRepository.findByIdAndSalonId(previousMediaId, command.salonId)?.let { previous ->
                previous.archive()
                mediaAssetRepository.save(previous)
            }
        }

        return saved
    }
}
