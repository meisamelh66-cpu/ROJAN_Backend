package ai.rojan.backend.application.media

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetTenantMismatchException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

/** Explicit `null` clears that slot - see [Salon.assignIdentityMedia]'s own doc comment. */
data class AssignSalonIdentityMediaCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val logoMediaId: MediaAssetId?,
    val coverMediaId: MediaAssetId?,
)

/**
 * Owner-only ([Permission.MANAGE_SALON]). The core tenant-isolation check
 * for this whole phase: an already-uploaded [ai.rojan.backend.domain.media.MediaAsset]
 * can only become a salon's logo/cover if it actually belongs to *that*
 * salon - an owner with two salons cannot point salon A's logo at
 * something uploaded for salon B, even though they own both.
 */
class AssignSalonIdentityMediaUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: AssignSalonIdentityMediaCommand): Salon {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)

        command.logoMediaId?.let { requireBelongsToSalon(it, salon.id) }
        command.coverMediaId?.let { requireBelongsToSalon(it, salon.id) }

        salon.assignIdentityMedia(logoMediaId = command.logoMediaId, coverMediaId = command.coverMediaId)
        return salonRepository.save(salon)
    }

    private fun requireBelongsToSalon(mediaAssetId: MediaAssetId, salonId: SalonId) {
        val mediaAsset = mediaAssetRepository.findById(mediaAssetId)
            ?: throw MediaAssetTenantMismatchException(mediaAssetId.value.toString(), salonId.value.toString())
        if (mediaAsset.salonId != salonId) {
            throw MediaAssetTenantMismatchException(mediaAssetId.value.toString(), salonId.value.toString())
        }
    }
}
