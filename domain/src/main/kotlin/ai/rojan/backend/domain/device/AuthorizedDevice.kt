package ai.rojan.backend.domain.device

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class AuthorizedDeviceId(val value: UUID) {
    companion object {
        fun new(): AuthorizedDeviceId = AuthorizedDeviceId(UUID.randomUUID())
    }
}

/**
 * Desktop Device Authorization Foundation (Phase A): one association between
 * an authenticated [ai.rojan.backend.domain.user.User] and a specific
 * Windows Reception installation, scoped to exactly one
 * [ai.rojan.backend.domain.salon.Salon] - see [salonId]'s own doc comment
 * for why this is never a global "this user's device" row. [deviceId] is
 * the Desktop client's own locally-minted, stable identifier (see
 * ROJAN_Desktop's `DeviceRegistrationService` - a random GUID, never derived
 * from hardware); this entity never generates or reinterprets its shape,
 * only records it, the same "authoritative format lives with the caller"
 * split this codebase already uses for other client-supplied free-text
 * identifiers.
 *
 * Deliberately no `active` boolean: a row's authorization state is entirely
 * [revokedAt] - `null` means authorized, non-null means it never
 * automatically reverts. There is no `revoke()` method on this entity yet -
 * this phase only builds the foundation (record + detect + refuse revival);
 * an actual revoke action is explicitly out of scope here (see V39's own
 * migration comment and the Phase A task's own instructions).
 */
class AuthorizedDevice private constructor(
    val id: AuthorizedDeviceId,
    val userId: UserId,
    /**
     * The one salon this authorization row is scoped to. The same physical
     * [deviceId] can legitimately hold a separate, independent row per salon
     * a manager/receptionist genuinely operates (a shared front-desk PC, or
     * one owner across two of their own salons) - registering for a second
     * salon must never touch or reassign the row already registered for the
     * first. See the V39 migration's own doc comment for the matching
     * `UNIQUE (user_id, salon_id, device_id)` constraint this mirrors.
     */
    val salonId: SalonId,
    val deviceId: String,
    val fingerprint: String?,
    val installationId: String?,
    val registeredAt: Instant,
    lastSeenAt: Instant?,
    revokedAt: Instant?,
) {
    var lastSeenAt: Instant? = lastSeenAt
        private set

    var revokedAt: Instant? = revokedAt
        private set

    val isRevoked: Boolean get() = revokedAt != null

    /**
     * Registration heartbeat for an already-authorized row -
     * [ai.rojan.backend.application.device.RegisterDeviceUseCase] is the
     * only caller, and only on its already-not-revoked branch. Guarded
     * (not merely "never called") on a revoked row on purpose: a revoked
     * device's [lastSeenAt] must freeze at whatever it was before
     * revocation, never advance, so it can never look like recent
     * legitimate activity.
     */
    fun recordSeen() {
        check(!isRevoked) { "A revoked device's lastSeenAt must never be updated" }
        lastSeenAt = Instant.now()
    }

    companion object {
        fun register(
            userId: UserId,
            salonId: SalonId,
            deviceId: String,
            fingerprint: String?,
            installationId: String?,
        ): AuthorizedDevice {
            require(deviceId.isNotBlank()) { "deviceId must not be blank" }
            val now = Instant.now()
            return AuthorizedDevice(
                id = AuthorizedDeviceId.new(),
                userId = userId,
                salonId = salonId,
                deviceId = deviceId,
                fingerprint = fingerprint?.trim()?.ifBlank { null },
                installationId = installationId?.trim()?.ifBlank { null },
                registeredAt = now,
                lastSeenAt = now,
                revokedAt = null,
            )
        }

        fun reconstitute(
            id: AuthorizedDeviceId,
            userId: UserId,
            salonId: SalonId,
            deviceId: String,
            fingerprint: String?,
            installationId: String?,
            registeredAt: Instant,
            lastSeenAt: Instant?,
            revokedAt: Instant?,
        ): AuthorizedDevice = AuthorizedDevice(id, userId, salonId, deviceId, fingerprint, installationId, registeredAt, lastSeenAt, revokedAt)
    }
}
