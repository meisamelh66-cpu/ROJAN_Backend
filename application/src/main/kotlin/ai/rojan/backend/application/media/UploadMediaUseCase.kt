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
    val fileName: String,
    val mimeType: String,
    val content: ByteArray,
)

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

        // A fresh random component, not the eventual MediaAsset.id (which
        // doesn't exist yet at this point, before MediaAsset.create()/save())
        // - the storage key only needs to be unique and salon-scoped, not
        // tied to the primary key.
        val storageToken = UUID.randomUUID()
        val extension = command.fileName.substringAfterLast('.', missingDelimiterValue = "")
        val storageKey = buildString {
            append("salon/").append(salon.id.value).append('/').append(storageToken)
            if (extension.isNotBlank()) append('.').append(extension)
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
