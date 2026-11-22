package ai.rojan.backend.api.auth

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.auth.ActivateReviewerAccessCommand
import ai.rojan.backend.application.auth.ActivateReviewerAccessUseCase
import ai.rojan.backend.application.auth.DeactivateReviewerAccessCommand
import ai.rojan.backend.application.auth.DeactivateReviewerAccessUseCase
import ai.rojan.backend.domain.auth.ReviewerAccessGrant
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Cafe Bazaar reviewer access — PLATFORM_ADMIN-only activation/deactivation
 * of the isolated, additive mechanism [ai.rojan.backend.application.auth.TryReviewerAccessLoginUseCase]
 * checks inside [AuthController.verifyOtp]. Authorization is entirely
 * [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver]
 * inside each use case, never checked here directly — the same convention
 * [ai.rojan.backend.api.apprelease.AppReleaseController] already uses.
 *
 * This controller never touches [ai.rojan.backend.application.auth.VerifyOtpUseCase]
 * or the real OTP tables in any way — it only ever reads/writes the fully
 * separate `reviewer_access_grants` table.
 */
@RestController
@RequestMapping("/api/v1/platform-authority/reviewer-access")
@Tag(name = "Platform Authority - Reviewer Access")
class ReviewerAccessController(
    private val activateReviewerAccessUseCase: ActivateReviewerAccessUseCase,
    private val deactivateReviewerAccessUseCase: DeactivateReviewerAccessUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Activate a 7-day (default) fixed-code reviewer login for one phone number that already has a real account (PLATFORM_ADMIN only)")
    fun activate(
        @Valid @RequestBody request: ActivateReviewerAccessRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ReviewerAccessGrantResponse {
        val callerId = currentUserResolver.resolve(principal)
        val grant = activateReviewerAccessUseCase.execute(
            ActivateReviewerAccessCommand(
                callerId = callerId,
                phoneNumber = request.phoneNumber,
                code = request.code,
                validityDays = request.validityDays ?: 7,
            ),
        )
        return grant.toResponse()
    }

    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Immediately revoke reviewer access for one phone number (PLATFORM_ADMIN only) - idempotent, safe to call even if nothing is active")
    fun deactivate(
        @Valid @RequestBody request: DeactivateReviewerAccessRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        deactivateReviewerAccessUseCase.execute(DeactivateReviewerAccessCommand(callerId = callerId, phoneNumber = request.phoneNumber))
    }

    private fun ReviewerAccessGrant.toResponse() = ReviewerAccessGrantResponse(
        phoneNumber = phoneNumber.value,
        activatedAt = activatedAt,
        expiresAt = expiresAt,
        active = active,
    )
}

data class ActivateReviewerAccessRequest(
    @field:NotBlank
    val phoneNumber: String,
    @field:NotBlank
    val code: String,
    /** Defaults to 7 days when omitted — never required to be sent explicitly for the standard Cafe Bazaar review window. */
    val validityDays: Long? = null,
)

data class DeactivateReviewerAccessRequest(
    @field:NotBlank
    val phoneNumber: String,
)

/** Never includes the code or its hash — same "never echo the secret back" discipline as every other credential-bearing response in this codebase. */
data class ReviewerAccessGrantResponse(
    val phoneNumber: String,
    val activatedAt: Instant,
    val expiresAt: Instant,
    val active: Boolean,
)
