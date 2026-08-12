package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.CreateSalonInviteCommand
import ai.rojan.backend.application.salon.CreateSalonInviteUseCase
import ai.rojan.backend.application.salon.GenerateSalonInviteQrCodeCommand
import ai.rojan.backend.application.salon.GenerateSalonInviteQrCodeUseCase
import ai.rojan.backend.application.salon.ListSalonInvitesCommand
import ai.rojan.backend.application.salon.ListSalonInvitesUseCase
import ai.rojan.backend.application.salon.RevokeSalonInviteCommand
import ai.rojan.backend.application.salon.RevokeSalonInviteUseCase
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonInvite
import ai.rojan.backend.domain.salon.SalonInviteId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Owner-side staff-invite management, nested under the salon it belongs to
 * (tenant isolation by path, mirroring every other `/salons/{salonId}/...`
 * sub-resource controller). The complementary anonymous/accept endpoints
 * live at the top-level `/api/v1/invites/{token}` in [ai.rojan.backend.api.invite.InviteController] -
 * split the same way the public/authenticated salon endpoints already are
 * between `SalonController` and `PublicSalonController`.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/invites")
@Tag(name = "Salon Invites")
class SalonInviteController(
    private val createSalonInviteUseCase: CreateSalonInviteUseCase,
    private val listSalonInvitesUseCase: ListSalonInvitesUseCase,
    private val revokeSalonInviteUseCase: RevokeSalonInviteUseCase,
    private val generateSalonInviteQrCodeUseCase: GenerateSalonInviteQrCodeUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a staff invite for this salon (owner only)")
    fun create(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: CreateSalonInviteRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonInviteResponse {
        val callerId = currentUserResolver.resolve(principal)
        val invite = createSalonInviteUseCase.execute(CreateSalonInviteCommand(SalonId(salonId), callerId, request.role))
        return invite.toResponse()
    }

    @GetMapping
    @Operation(summary = "List staff invites for this salon (owner only)")
    fun list(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): List<SalonInviteResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listSalonInvitesUseCase.execute(ListSalonInvitesCommand(SalonId(salonId), callerId)).map { it.toResponse() }
    }

    @DeleteMapping("/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a pending staff invite (owner only)")
    fun revoke(
        @PathVariable salonId: UUID,
        @PathVariable inviteId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        revokeSalonInviteUseCase.execute(RevokeSalonInviteCommand(SalonId(salonId), SalonInviteId(inviteId), callerId))
    }

    @GetMapping("/{inviteId}/qr-code", produces = [MediaType.IMAGE_PNG_VALUE])
    @Operation(summary = "Generate a scannable QR code (PNG) encoding this invite's accept link (owner only)")
    fun qrCode(
        @PathVariable salonId: UUID,
        @PathVariable inviteId: UUID,
        @RequestParam(defaultValue = "512") size: Int,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<ByteArray> {
        val callerId = currentUserResolver.resolve(principal)
        val png = generateSalonInviteQrCodeUseCase.execute(
            GenerateSalonInviteQrCodeCommand(SalonId(salonId), SalonInviteId(inviteId), callerId, size),
        )
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png)
    }

    private fun SalonInvite.toResponse() = SalonInviteResponse(
        id = id.value,
        salonId = salonId.value,
        role = role,
        token = token,
        status = currentStatus(),
        expiresAt = expiresAt,
        createdBy = createdBy.value,
        acceptedBy = acceptedBy?.value,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
