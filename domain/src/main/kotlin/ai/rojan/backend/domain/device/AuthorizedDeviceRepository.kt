package ai.rojan.backend.domain.device

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId

/**
 * Output port for [AuthorizedDevice] persistence. Only the one operation
 * [ai.rojan.backend.application.device.RegisterDeviceUseCase] actually
 * needs this phase - the idempotency lookup backing the same
 * `(user_id, salon_id, device_id)` key the V39 migration's own unique
 * constraint enforces. No list/find-by-id/revoke methods yet - genuinely
 * not needed until a future phase actually builds a caller for them (see
 * the Phase A task's own "do not invent additional business rules"
 * instruction).
 */
interface AuthorizedDeviceRepository {
    fun save(device: AuthorizedDevice): AuthorizedDevice

    fun findByUserIdAndSalonIdAndDeviceId(userId: UserId, salonId: SalonId, deviceId: String): AuthorizedDevice?
}
