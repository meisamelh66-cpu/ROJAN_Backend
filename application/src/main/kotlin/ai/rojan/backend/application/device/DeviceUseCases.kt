package ai.rojan.backend.application.device

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.device.AuthorizedDevice
import ai.rojan.backend.domain.device.AuthorizedDeviceRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId

data class RegisterDeviceCommand(
    val callerId: UserId,
    val salonId: SalonId,
    val deviceId: String,
    val fingerprint: String?,
    val installationId: String?,
)

/**
 * Desktop Device Authorization Foundation (Phase A): the caller's OWN
 * resolved salon access is the sole authority for [RegisterDeviceCommand.salonId]
 * - resolved fresh via [SalonPermissionResolver], never trusted from the
 * request body by itself (mirrors that class's own "resolved fresh, never
 * cached" model unchanged). Any real access at all (owner, any
 * [ai.rojan.backend.domain.salon.SalonRole] membership, or an own
 * [ai.rojan.backend.domain.salon.Specialist] link) is sufficient - there is
 * no dedicated device-registration [ai.rojan.backend.domain.salon.Permission]
 * value, matching this phase's "do not invent additional business rules"
 * instruction; a caller with zero resolved permissions at that salon has no
 * real relationship to it and is refused identically to every other
 * salon-scoped use case in this codebase.
 *
 * Idempotent for the exact same (caller, salon, deviceId): a repeat call
 * updates [AuthorizedDevice.lastSeenAt] on the existing row rather than
 * creating a duplicate - the database's own unique constraint (V39) backs
 * this even under a concurrent race; this lookup is the normal-path
 * short-circuit, not the only enforcement. A revoked existing row is
 * returned completely untouched - see [AuthorizedDevice.recordSeen]'s own
 * guard for why it is never silently reactivated by presenting the same
 * identity again. Presenting the same `deviceId` for a *different* salon
 * never touches the original salon's row at all - it is a distinct
 * (caller, salon, device) key, so this simply registers a second,
 * independent association, never a reassignment.
 */
class RegisterDeviceUseCase(
    private val salonPermissionResolver: SalonPermissionResolver,
    private val deviceRepository: AuthorizedDeviceRepository,
) {
    fun execute(command: RegisterDeviceCommand): AuthorizedDevice {
        val deviceId = command.deviceId.trim()
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val permissions = salonPermissionResolver.resolve(command.salonId, command.callerId)
        if (permissions.isEmpty()) {
            throw SalonAccessDeniedException(command.salonId.value.toString())
        }

        val existing = deviceRepository.findByUserIdAndSalonIdAndDeviceId(command.callerId, command.salonId, deviceId)
        if (existing != null) {
            if (existing.isRevoked) {
                return existing
            }
            existing.recordSeen()
            return deviceRepository.save(existing)
        }

        val device = AuthorizedDevice.register(
            userId = command.callerId,
            salonId = command.salonId,
            deviceId = deviceId,
            fingerprint = command.fingerprint,
            installationId = command.installationId,
        )
        return deviceRepository.save(device)
    }
}
