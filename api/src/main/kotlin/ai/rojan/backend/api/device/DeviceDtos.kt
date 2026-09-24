package ai.rojan.backend.api.device

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * [salonId] is only ever a *claim* to be verified - the backend re-resolves
 * the caller's own real access to it (owner/membership/specialist link) via
 * the existing [ai.rojan.backend.application.salon.SalonPermissionResolver]
 * before anything is written; it is never trusted by itself. No `userId`
 * field on purpose - the authenticated caller (JWT/security context) is the
 * only user this can ever register a device for.
 */
data class RegisterDeviceRequest(
    @field:NotNull
    val salonId: UUID,

    @field:NotBlank
    @field:Size(max = 200)
    @field:Schema(example = "3f2a1c9e4b6d4f0aa5c9e7d1b2a3c4d5")
    val deviceId: String,

    @field:Size(max = 200)
    val fingerprint: String? = null,

    @field:Size(max = 200)
    val installationId: String? = null,
)

/** Minimal, safe representation - never echoes [RegisterDeviceRequest.fingerprint]/[RegisterDeviceRequest.installationId] back on the wire. */
data class AuthorizedDeviceResponse(
    val id: UUID,
    val salonId: UUID,
    val deviceId: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant?,
    val revokedAt: Instant?,
)
