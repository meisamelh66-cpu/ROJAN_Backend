package ai.rojan.backend.api.auth

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

private const val E164_PATTERN = "^\\+[1-9]\\d{7,14}$"

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
    @field:Size(min = 6, max = 6)
    @field:Schema(example = "482913")
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
