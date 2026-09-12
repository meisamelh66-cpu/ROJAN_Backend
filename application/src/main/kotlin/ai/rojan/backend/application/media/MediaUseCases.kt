package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.MediaReorderMismatchException
import ai.rojan.backend.domain.common.MediaSizeExceededException
import ai.rojan.backend.domain.common.MediaTargetRequiredException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.common.MediaTypeMismatchException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaAssetStatus
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.media.TARGET_REQUIRED_MEDIA_TYPES
import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId
import java.util.UUID

private val PUBLIC_IMAGE_TYPES = setOf(
    MediaType.LOGO, MediaType.COVER, MediaType.GALLERY, MediaType.PORTFOLIO,
    MediaType.SPECIALIST_PHOTO, MediaType.SERVICE_IMAGE,
)
private val ALLOWED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
// Document Archive (Phase 2) Security Gate §10.3: a license/certificate is
// as often a phone-camera photo as a scanned PDF - PDF-only would reject
// the common case.
private val ALLOWED_DOCUMENT_MIME_TYPES = setOf("application/pdf", "image/jpeg", "image/png")
private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
private const val MAX_DOCUMENT_BYTES = 20L * 1024 * 1024

/**
 * Detects the true image format of [content] from its leading bytes (magic
 * numbers), independent of whatever the client claimed via the
 * `Content-Type` header or filename - both are client-controlled and
 * independently spoofable (e.g. `Content-Type: image/png` on an
 * `evil.html` payload, which Nginx's `/media/` static location would then
 * serve as text/html from the API's own origin). Only recognizes the
 * formats [ALLOWED_IMAGE_MIME_TYPES] allows - a declared type outside this
 * set fails closed (rejected) rather than being accepted unvalidated.
 * Scoped to [PUBLIC_IMAGE_TYPES] only (`LOGO`/`COVER`/`GALLERY`/`PORTFOLIO`/
 * `SPECIALIST_PHOTO`/`SERVICE_IMAGE`) - `DOCUMENT` uploads (PDF or
 * photographed) are Document Archive's own concern, not extended here.
 * `internal`, not `private` (Phase 5A.2): reused as-is by
 * `UserProfileMediaUseCases.kt` for AVATAR/PROFILE_COVER uploads - the
 * same reasoning, not a second sniffer.
 */
internal object ImageContentSniffer {

    /** The only extensions [UploadMediaUseCase] will ever write to disk for a public image - never derived from client input. */
    val EXTENSIONS_BY_MIME_TYPE = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
    )

    fun detect(content: ByteArray): String? = when {
        content.size >= 8 &&
            content[0] == 0x89.toByte() && content[1] == 0x50.toByte() &&
            content[2] == 0x4E.toByte() && content[3] == 0x47.toByte() &&
            content[4] == 0x0D.toByte() && content[5] == 0x0A.toByte() &&
            content[6] == 0x1A.toByte() && content[7] == 0x0A.toByte() -> "image/png"

        content.size >= 3 &&
            content[0] == 0xFF.toByte() && content[1] == 0xD8.toByte() && content[2] == 0xFF.toByte() -> "image/jpeg"

        content.size >= 12 &&
            content[0] == 'R'.code.toByte() && content[1] == 'I'.code.toByte() &&
            content[2] == 'F'.code.toByte() && content[3] == 'F'.code.toByte() &&
            content[8] == 'W'.code.toByte() && content[9] == 'E'.code.toByte() &&
            content[10] == 'B'.code.toByte() && content[11] == 'P'.code.toByte() -> "image/webp"

        else -> null
    }
}

data class UploadMediaCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val mediaType: MediaType,
    val content: ByteArray,
    val originalName: String,
    val mimeType: String,
    val targetId: UUID? = null,
)

/**
 * Mime allow-list and size ceilings are application-layer policy, not a
 * [MediaAsset] domain invariant - they're configuration-shaped rules about
 * what this platform currently accepts, not a structural truth about what
 * a media asset *is* (unlike, say, a blank storage key).
 *
 * [targetId] validation (Media System Evolution v2):
 * [ai.rojan.backend.domain.media.TARGET_REQUIRED_MEDIA_TYPES] (`PORTFOLIO`/
 * `SERVICE_IMAGE`) reject a `null` targetId outright, then verify the id
 * actually names a [ai.rojan.backend.domain.salon.Specialist]/
 * [ai.rojan.backend.domain.salon.Service] belonging to *this* salon - the
 * same tenant-isolation shape every other salon-scoped lookup in this
 * codebase already enforces (e.g. `ServiceController.findServiceOrThrow`).
 * Every other [MediaType] ignores whatever `targetId` it's given rather
 * than rejecting it - `SPECIALIST_PHOTO` in particular stays
 * targetId-optional so the Media Sprint P0 upload call (which never sent
 * one) keeps working unchanged.
 */
class UploadMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val mediaStoragePort: MediaStoragePort,
    private val specialistRepository: SpecialistRepository,
    private val serviceRepository: ServiceRepository,
) {
    fun execute(command: UploadMediaCommand): MediaAsset {
        salonRepository.findById(command.salonId) ?: throw SalonNotFoundException(command.salonId.value.toString())
        // Document Archive (Phase 2): DOCUMENT uploads require MANAGE_DOCUMENTS,
        // never the weaker MANAGE_MEDIA - closes the gap where a Manager
        // (MANAGE_MEDIA but not MANAGE_DOCUMENTS, per the approved matrix)
        // could otherwise upload compliance documents.
        val requiredPermission = if (command.mediaType == MediaType.DOCUMENT) Permission.MANAGE_DOCUMENTS else Permission.MANAGE_MEDIA
        salonPermissionResolver.require(command.salonId, command.callerId, requiredPermission)

        validateTarget(command.salonId, command.mediaType, command.targetId)

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

        // The declared Content-Type is client-controlled and independently
        // spoofable from the actual bytes - only the real leading bytes of
        // [command.content] decide what a public image really is and what
        // extension it gets written with. DOCUMENT is untouched here (PDF
        // magic-byte sniffing is out of this scope).
        val detectedImageMimeType = if (isPublicImage) ImageContentSniffer.detect(command.content) else null
        if (isPublicImage && (detectedImageMimeType == null || detectedImageMimeType != command.mimeType)) {
            throw MediaTypeInvalidException(command.mimeType, command.mediaType.name)
        }

        // Document Archive (Phase 2) Security Gate §10.1: DOCUMENT content
        // lives under a distinct, private-only prefix - never the public
        // media/ prefix, so a bucket policy can grant public read on
        // media/ while denying it entirely on documents/.
        val prefix = if (command.mediaType == MediaType.DOCUMENT) "documents" else "media"
        val extension = detectedImageMimeType?.let { ImageContentSniffer.EXTENSIONS_BY_MIME_TYPE.getValue(it) }
        val storageKey = buildString {
            append("salons/").append(command.salonId.value).append('/').append(prefix).append('/').append(UUID.randomUUID())
            if (extension != null) append('.').append(extension)
        }
        mediaStoragePort.upload(storageKey, command.content, command.mimeType)

        // Append at the end of whatever (salonId, mediaType, targetId) group
        // this upload lands in - a brand-new gallery/portfolio/service-image
        // slot always shows up last, never reshuffling what's already there.
        val nextDisplayOrder = mediaAssetRepository.findBySalonId(command.salonId, command.mediaType, command.targetId)
            .filter { it.status != MediaAssetStatus.DELETED }
            .maxOfOrNull { it.displayOrder }
            ?.plus(1) ?: 0

        val mediaAsset = MediaAsset.create(
            salonId = command.salonId,
            mediaType = command.mediaType,
            storageKey = storageKey,
            originalName = command.originalName,
            mimeType = command.mimeType,
            fileSize = actualBytes,
            uploadedBy = command.callerId,
            targetId = command.targetId,
            displayOrder = nextDisplayOrder,
        )
        return mediaAssetRepository.save(mediaAsset)
    }

    private fun validateTarget(salonId: SalonId, mediaType: MediaType, targetId: UUID?) {
        if (mediaType !in TARGET_REQUIRED_MEDIA_TYPES) return
        if (targetId == null) throw MediaTargetRequiredException(mediaType.name)
        when (mediaType) {
            MediaType.PORTFOLIO -> {
                val specialist = specialistRepository.findById(SpecialistId(targetId))
                    ?.takeIf { it.salonId == salonId }
                    ?: throw SpecialistNotFoundException(targetId.toString())
                specialist
            }
            MediaType.SERVICE_IMAGE -> {
                val service = serviceRepository.findById(ServiceId(targetId))
                    ?.takeIf { it.salonId == salonId }
                    ?: throw ServiceNotFoundException(targetId.toString())
                service
            }
            else -> Unit
        }
    }
}

data class ListMediaQuery(
    val salonId: SalonId,
    val mediaType: MediaType?,
    val targetId: UUID? = null,
)

class ListMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
) {
    /**
     * No permission gate beyond a valid salon - this is the *public* media
     * surface (logo/cover/gallery/portfolio/specialist photos/service
     * images), intentionally open to any authenticated caller. Document
     * Archive (Phase 2) Security Gate §10.1: `DOCUMENT`-typed assets are
     * unconditionally excluded here, regardless of any `mediaType` filter
     * the caller passes - documents are discoverable exclusively through
     * the permission-gated `GET /salons/{salonId}/documents` surface, never
     * this one. Results come back pre-sorted by `displayOrder` (the
     * repository's own contract) - callers never need to re-sort.
     */
    fun execute(query: ListMediaQuery): List<MediaAsset> {
        salonRepository.findById(query.salonId) ?: throw SalonNotFoundException(query.salonId.value.toString())
        return mediaAssetRepository.findBySalonId(query.salonId, query.mediaType, query.targetId)
            .filter { it.status != MediaAssetStatus.DELETED && it.mediaType != MediaType.DOCUMENT }
    }
}

data class ReorderMediaCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val mediaType: MediaType,
    val targetId: UUID?,
    val orderedMediaIds: List<MediaAssetId>,
)

/**
 * Complete Salon Gallery UX (Media System Evolution v2): assigns sequential
 * [MediaAsset.displayOrder] values (`0..n-1`) matching [ReorderMediaCommand.orderedMediaIds]'
 * position. Every id must already belong to the exact (salonId, mediaType,
 * targetId) group being reordered - a stray id (wrong salon, wrong type,
 * wrong target, or simply unknown) fails the whole call rather than
 * silently reordering a subset, so a caller's local list can never drift
 * from the server's.
 */
class ReorderMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: ReorderMediaCommand) {
        salonRepository.findById(command.salonId) ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(command.salonId, command.callerId, Permission.MANAGE_MEDIA)

        val group = mediaAssetRepository.findBySalonId(command.salonId, command.mediaType, command.targetId)
            .filter { it.status != MediaAssetStatus.DELETED }
            .associateBy { it.id }

        val assets = command.orderedMediaIds.map { id ->
            group[id] ?: throw MediaReorderMismatchException(id.value.toString())
        }

        assets.forEachIndexed { index, asset ->
            asset.reorder(index)
            mediaAssetRepository.save(asset)
        }
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
