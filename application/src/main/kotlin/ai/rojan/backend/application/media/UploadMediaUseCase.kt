package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaFileTooLargeException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UnsupportedMediaTypeException
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaOwnerType
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import java.util.UUID

data class UploadMediaCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val mediaType: MediaType,
    /** Client-supplied original filename - stored as display metadata only. Never trusted for the on-disk storage extension (see [ImageContentSniffer]), since a client controls both this and [mimeType] and either can be spoofed independently of the actual bytes in [content]. */
    val fileName: String,
    val mimeType: String,
    val content: ByteArray,
)

/**
 * Detects the true image format of [content] from its leading bytes
 * (magic numbers), independent of whatever the client claimed via the
 * `Content-Type` header or filename extension. Only recognizes the formats
 * [MediaProperties][ai.rojan.backend.infrastructure.media.MediaProperties]
 * defaults to allowing - a declared type outside this set fails closed
 * (rejected) rather than being accepted unvalidated, even if an operator
 * adds it to `rojan.media.allowed-mime-types` without extending this
 * sniffer.
 */
private object ImageContentSniffer {

    /** The only extensions this use case will ever write to disk - never derived from client input. */
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

/**
 * Owner-only ([Permission.MANAGE_SALON] — logo/cover/gallery/portfolio are
 * all salon-identity edits, the same gate [UpdateSalonUseCase] already
 * uses). [ownerType]/[ownerId] are not command inputs: for the one owner
 * kind wired this phase ([MediaOwnerType.SALON]) they're fully determined
 * by [UploadMediaCommand.salonId] — accepting them as separate,
 * caller-supplied fields here would only create room for a mismatched
 * value, not real flexibility, since [MediaOwnerType] has no other case
 * yet. The [MediaAsset] schema itself stays polymorphic for when that
 * changes.
 */
class UploadMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val allowedMimeTypes: Set<String>,
    private val maxFileSizeBytes: Long,
) {
    fun execute(command: UploadMediaCommand): MediaAsset {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)

        if (command.mimeType !in allowedMimeTypes) {
            throw UnsupportedMediaTypeException(command.mimeType)
        }
        val fileSize = command.content.size.toLong()
        if (fileSize > maxFileSizeBytes) {
            throw MediaFileTooLargeException(fileSize, maxFileSizeBytes)
        }
        // The declared Content-Type and filename are both client-controlled and
        // independently spoofable (e.g. `Content-Type: image/png` on an
        // `evil.html` payload) - only the actual leading bytes of [content]
        // decide what this really is and what extension it gets written with.
        val detectedMimeType = ImageContentSniffer.detect(command.content)
        if (detectedMimeType == null || detectedMimeType != command.mimeType) {
            throw UnsupportedMediaTypeException(command.mimeType)
        }

        // A fresh random component, not the eventual MediaAsset.id (which
        // doesn't exist yet at this point, before MediaAsset.create()/save())
        // - the storage key only needs to be unique and salon-scoped, not
        // tied to the primary key.
        val storageToken = UUID.randomUUID()
        val extension = ImageContentSniffer.EXTENSIONS_BY_MIME_TYPE.getValue(detectedMimeType)
        val storageKey = buildString {
            append("salon/").append(salon.id.value).append('/').append(storageToken).append('.').append(extension)
        }
        val url = mediaStoragePort.store(storageKey, command.content, command.mimeType)

        val mediaAsset = MediaAsset.create(
            salonId = salon.id,
            ownerType = MediaOwnerType.SALON,
            ownerId = salon.id.value,
            mediaType = command.mediaType,
            storageKey = storageKey,
            fileName = command.fileName,
            mimeType = command.mimeType,
            fileSize = fileSize,
            url = url,
        )
        return mediaAssetRepository.save(mediaAsset)
    }
}
