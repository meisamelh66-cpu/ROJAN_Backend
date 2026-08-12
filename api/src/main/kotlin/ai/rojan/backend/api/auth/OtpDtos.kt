package ai.rojan.backend.api.auth

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

private const val E164_PATTERN = "^\\+[1-9]\\d{7,14}$"

/**
 * Structural sanity bound only (digits, 4-8 characters) — the exact expected
 * length is [ai.rojan.backend.application.auth.OtpPolicy.codeLength], a
 * single runtime-configurable value the API layer has no compile-time
 * knowledge of. A code of the wrong length simply won't hash-match what's
 * stored in Redis and is rejected as an ordinary invalid OTP by
 * `VerifyOtpUseCase` — duplicating an exact-length check here would just be
 * a second, harder-to-keep-in-sync source of truth.
 */
private const val OTP_CODE_PATTERN = "^\\d{4,8}$"

data class OtpRequestRequest(
    @field:NotBlank
    @field:Pattern(regexp = E164_PATTERN, message = "phoneNumber must be in E.164 format, e.g. +989123456789")
    @field:Schema(example = "+989123456789")
    val phoneNumber: String,
)

data class OtpResendRequest(
    @field:NotBlank
    @field:Pattern(regexp = E164_PATTERN, message = "phoneNumber must be in E.164 format, e.g. +989123456789")
    @field:Schema(example = "+989123456789")
    val phoneNumber: String,
)

data class OtpVerifyRequest(
    @field:NotBlank
    @field:Pattern(regexp = E164_PATTERN, message = "phoneNumber must be in E.164 format, e.g. +989123456789")
    @field:Schema(example = "+989123456789")
    val phoneNumber: String,

    @field:NotBlank
    @field:Pattern(regexp = OTP_CODE_PATTERN, message = "code must be 4-8 digits")
    @field:Schema(example = "4821")
    val code: String,

    @field:Size(max = 255)
    @field:Schema(example = "Jane Doe", description = "Used only the first time this phone number completes verification (new-account creation); ignored for an existing account")
    val fullName: String? = null,
)

data class OtpIssuedResponse(
    @field:Schema(example = "+989123456789")
    val phoneNumber: String,

    @field:Schema(description = "Seconds until the issued code expires")
    val expiresInSeconds: Long,

    @field:Schema(description = "Seconds the caller must wait before requesting another code for this phone number")
    val canResendAfterSeconds: Long,
)
