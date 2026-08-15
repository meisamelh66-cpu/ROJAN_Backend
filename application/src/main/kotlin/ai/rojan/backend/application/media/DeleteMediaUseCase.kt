package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.user.UserId

data class DeleteMediaCommand(val mediaAssetId: MediaAssetId, val callerId: UserId)

class DeleteMediaUseCase(
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: DeleteMediaCommand) {
        val mediaAsset = mediaAssetRepository.findById(command.mediaAssetId)
            ?: throw MediaAssetNotFoundException(command.mediaAssetId.value.toString())
        salonPermissionResolver.require(mediaAsset.salonId, command.callerId, Permission.MANAGE_SALON)
        mediaStoragePort.delete(mediaAsset.storageKey)
        mediaAssetRepository.delete(mediaAsset.id)
    }
}
