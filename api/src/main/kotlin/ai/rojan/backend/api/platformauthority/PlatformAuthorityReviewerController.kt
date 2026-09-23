package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.platformauthority.CreatePlatformReviewerCommand
import ai.rojan.backend.application.platformauthority.CreatePlatformReviewerUseCase
import ai.rojan.backend.application.platformauthority.DeactivatePlatformReviewerCommand
import ai.rojan.backend.application.platformauthority.DeactivatePlatformReviewerUseCase
import ai.rojan.backend.application.platformauthority.ListPlatformReviewersQuery
import ai.rojan.backend.application.platformauthority.ListPlatformReviewersUseCase
import ai.rojan.backend.application.platformauthority.ReactivatePlatformReviewerCommand
import ai.rojan.backend.application.platformauthority.ReactivatePlatformReviewerUseCase
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform Reviewer Management (Phase 5) - PLATFORM_ADMIN only, every operation. A
 * PLATFORM_REVIEWER caller reaches [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver.requirePlatformAdmin]'s
 * `throw` inside each use case the same as any other non-admin caller, mapped to `403` by
 * [ai.rojan.backend.api.common.GlobalExceptionHandler] - there is no separate check here, the
 * controller never inspects [ai.rojan.backend.domain.user.User.role] itself. Reuses the existing
 * Phone+OTP authentication path wholesale - a created reviewer signs in exactly like any other
 * phone-registered account; nothing here issues a token or creates a second auth mechanism.
 */
@RestController
@RequestMapping("/api/v1/platform-authority/reviewers")
@Tag(name = "Platform Authority - Reviewers")
class PlatformAuthorityReviewerController(
    private val createPlatformReviewerUseCase: CreatePlatformReviewerUseCase,
    private val listPlatformReviewersUseCase: ListPlatformReviewersUseCase,
    private val deactivatePlatformReviewerUseCase: DeactivatePlatformReviewerUseCase,
    private val reactivatePlatformReviewerUseCase: ReactivatePlatformReviewerUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a PLATFORM_REVIEWER account (PLATFORM_ADMIN only)")
    fun create(
        @Valid @RequestBody request: CreatePlatformReviewerRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformReviewerResponse {
        val callerId = currentUserResolver.resolve(principal)
        val reviewer = createPlatformReviewerUseCase.execute(
            CreatePlatformReviewerCommand(callerId, PhoneNumber(request.phoneNumber), request.fullName),
        )
        return reviewer.toResponse()
    }

    @GetMapping
    @Operation(summary = "List every PLATFORM_REVIEWER account (PLATFORM_ADMIN only)")
    fun list(@AuthenticationPrincipal principal: UserDetails): List<PlatformReviewerResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listPlatformReviewersUseCase.execute(ListPlatformReviewersQuery(callerId)).map { it.toResponse() }
    }

    @PostMapping("/{reviewerId}/deactivate")
    @Operation(summary = "Deactivate a PLATFORM_REVIEWER account (PLATFORM_ADMIN only)")
    fun deactivate(
        @PathVariable reviewerId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformReviewerResponse {
        val callerId = currentUserResolver.resolve(principal)
        return deactivatePlatformReviewerUseCase.execute(
            DeactivatePlatformReviewerCommand(callerId, UserId(reviewerId)),
        ).toResponse()
    }

    @PostMapping("/{reviewerId}/reactivate")
    @Operation(summary = "Reactivate a previously deactivated PLATFORM_REVIEWER account (PLATFORM_ADMIN only)")
    fun reactivate(
        @PathVariable reviewerId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformReviewerResponse {
        val callerId = currentUserResolver.resolve(principal)
        return reactivatePlatformReviewerUseCase.execute(
            ReactivatePlatformReviewerCommand(callerId, UserId(reviewerId)),
        ).toResponse()
    }

    private fun User.toResponse() = PlatformReviewerResponse(
        id = id.value,
        phoneNumber = phoneNumber?.value,
        fullName = fullName,
        active = active,
        createdAt = createdAt,
    )
}
