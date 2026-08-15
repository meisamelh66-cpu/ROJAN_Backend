package ai.rojan.backend.application.salon

import ai.rojan.backend.application.port.QrCodeGeneratorPort
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class GenerateSalonQrCodeCommand(val salonId: SalonId, val callerId: UserId, val sizePx: Int)

/**
 * Owner-only ([Permission.MANAGE_SALON]) - reusing that permission rather
 * than inventing a new one, since generating the printable/branded asset is
 * a salon-settings-level action; the underlying public URL it encodes is
 * already unauthenticated once the salon is active (see
 * `PublicSalonController`) - this only controls who can produce the asset.
 */
class GenerateSalonQrCodeUseCase(
    private val salonRepository: SalonRepository,
    private val qrCodeGeneratorPort: QrCodeGeneratorPort,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val publicBaseUrl: String,
) {
    fun execute(command: GenerateSalonQrCodeCommand): ByteArray {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)

        val url = "${publicBaseUrl.trimEnd('/')}/s/${salon.slug}"
        return qrCodeGeneratorPort.generatePng(url, command.sizePx)
    }
}
