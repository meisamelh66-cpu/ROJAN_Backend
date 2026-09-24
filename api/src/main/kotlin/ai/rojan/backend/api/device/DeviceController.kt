package ai.rojan.backend.api.device

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.device.RegisterDeviceCommand
import ai.rojan.backend.application.device.RegisterDeviceUseCase
import ai.rojan.backend.domain.device.AuthorizedDevice
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Desktop Device Authorization Foundation (Phase A): registers (or, for an
 * already-registered, non-revoked identity, heartbeats) the calling
 * Windows Reception installation against exactly one salon the caller
 * actually has access to - see [RegisterDeviceUseCase]'s own doc comment
 * for the full authorization/idempotency contract. No `GET`/list/revoke
 * endpoint yet - genuinely out of scope for this phase, not an oversight.
 */
@RestController
@RequestMapping("/api/v1/users/me/devices")
@Tag(name = "Devices")
class DeviceController(
    private val currentUserResolver: CurrentUserResolver,
    private val registerDeviceUseCase: RegisterDeviceUseCase,
) {

    @PostMapping
    @Operation(
        summary = "Register (or heartbeat) the caller's Desktop device for one salon they have access to",
        description = "salonId is a claim only - the caller's real access to it is re-resolved server-side (owner/membership/specialist link) before anything is written. Idempotent for the same (caller, salon, deviceId); a revoked prior registration is never silently revived.",
    )
    fun register(
        @Valid @RequestBody request: RegisterDeviceRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): AuthorizedDeviceResponse {
        val callerId = currentUserResolver.resolve(principal)
        val device = registerDeviceUseCase.execute(
            RegisterDeviceCommand(
                callerId = callerId,
                salonId = SalonId(request.salonId),
                deviceId = request.deviceId,
                fingerprint = request.fingerprint,
                installationId = request.installationId,
            ),
        )
        return device.toResponse()
    }

    private fun AuthorizedDevice.toResponse() = AuthorizedDeviceResponse(
        id = id.value,
        salonId = salonId.value,
        deviceId = deviceId,
        registeredAt = registeredAt,
        lastSeenAt = lastSeenAt,
        revokedAt = revokedAt,
    )
}
