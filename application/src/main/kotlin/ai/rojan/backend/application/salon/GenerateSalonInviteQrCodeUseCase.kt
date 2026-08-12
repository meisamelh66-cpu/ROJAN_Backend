package ai.rojan.backend.application.salon

import ai.rojan.backend.application.port.QrCodeGeneratorPort
import ai.rojan.backend.domain.common.SalonInviteNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonInviteId
import ai.rojan.backend.domain.salon.SalonInviteRepository
import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class GenerateSalonInviteQrCodeCommand(val salonId: SalonId, val inviteId: SalonInviteId, val callerId: UserId, val sizePx: Int)

/**
 * Owner-only ([Permission.MANAGE_MEMBERSHIP], same reasoning as
 * [CreateSalonInviteUseCase]) - reuses [QrCodeGeneratorPort] exactly like
 * [GenerateSalonQrCodeUseCase] does, just encoding an invite accept link
 * (`{publicBaseUrl}/invite/{token}`) instead of the public salon page.
 */
class GenerateSalonInviteQrCodeUseCase(
    private val salonRepository: SalonRepository,
    private val salonInviteRepository: SalonInviteRepository,
    private val qrCodeGeneratorPort: QrCodeGeneratorPort,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val publicBaseUrl: String,
) {
    fun execute(command: GenerateSalonInviteQrCodeCommand): ByteArray {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_MEMBERSHIP)

        val invite = salonInviteRepository.findById(command.inviteId)
            ?.takeIf { it.salonId == salon.id && it.currentStatus() == SalonInviteStatus.CREATED }
            ?: throw SalonInviteNotFoundException(command.inviteId.value.toString())

        val url = "${publicBaseUrl.trimEnd('/')}/invite/${invite.token}"
        return qrCodeGeneratorPort.generatePng(url, command.sizePx)
    }
}
