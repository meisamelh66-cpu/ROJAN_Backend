package ai.rojan.backend.api.invite

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.AcceptSalonInviteCommand
import ai.rojan.backend.application.salon.AcceptSalonInviteUseCase
import ai.rojan.backend.application.salon.GetSalonInviteCommand
import ai.rojan.backend.application.salon.GetSalonInviteUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The staff (Reception/Manager) QR-scan landing point - [get] is
 * unauthenticated (permitted in `SecurityConfig` for `GET` only, see that
 * class), [accept] requires an existing ROJAN login exactly like the
 * customer QR journey's public browsing (unauthenticated) vs. booking
 * (authenticated) split in `PublicSalonController`/`BookingController`.
 */
@RestController
@RequestMapping("/api/v1/invites/{token}")
@Tag(name = "Invites")
class InviteController(
    private val getSalonInviteUseCase: GetSalonInviteUseCase,
    private val acceptSalonInviteUseCase: AcceptSalonInviteUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "Resolve an invite token to the salon name and role it grants (unauthenticated - the confirmation screen before accepting)")
    fun get(@PathVariable token: String): InviteDetailsResponse {
        val details = getSalonInviteUseCase.execute(GetSalonInviteCommand(token))
        return InviteDetailsResponse(details.salonName, details.role)
    }

    @PostMapping("/accept")
    @Operation(summary = "Accept an invite, granting the authenticated caller its salon membership role")
    fun accept(@PathVariable token: String, @AuthenticationPrincipal principal: UserDetails): SalonInviteAcceptedResponse {
        val callerId = currentUserResolver.resolve(principal)
        val invite = acceptSalonInviteUseCase.execute(AcceptSalonInviteCommand(token, callerId))
        return SalonInviteAcceptedResponse(invite.salonId.value, invite.role)
    }
}
