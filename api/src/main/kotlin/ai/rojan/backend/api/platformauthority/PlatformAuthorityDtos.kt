package ai.rojan.backend.api.platformauthority

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class RejectSalonDocumentRequest(
    @field:NotBlank
    val reason: String,
)

/**
 * No `role` field, ever - [ai.rojan.backend.application.platformauthority.CreatePlatformReviewerCommand]'s
 * own doc comment: a reviewer account is always [ai.rojan.backend.domain.user.UserRole.PLATFORM_REVIEWER],
 * assigned server-side, never taken from caller input. This is the whole point: a client can never
 * self-select platform authority.
 */
data class CreatePlatformReviewerRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be E.164 format, e.g. +989123456789")
    @field:Schema(example = "+989123456789")
    val phoneNumber: String,

    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(example = "Reza Ahmadi")
    val fullName: String,
)

/** Deliberately narrower than [ai.rojan.backend.api.auth.UserResponse] - a reviewer account has no email/avatar/cover of interest to this admin-only management surface. */
data class PlatformReviewerResponse(
    val id: UUID,
    val phoneNumber: String?,
    val fullName: String,
    val active: Boolean,
    val createdAt: Instant,
)
