package ai.rojan.backend.api.auth

import ai.rojan.backend.domain.user.UserRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank
    @field:Email
    @field:Schema(example = "jane.doe@example.com")
    val email: String,

    @field:NotBlank
    @field:Size(min = 8, max = 128)
    @field:Schema(example = "supersecret123", minLength = 8)
    val password: String,

    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(example = "Jane Doe")
    val fullName: String,

    @field:NotNull
    @field:Schema(example = "CUSTOMER")
    val role: UserRole,
)

data class LoginRequest(
    @field:NotBlank
    @field:Email
    @field:Schema(example = "jane.doe@example.com")
    val email: String,

    @field:NotBlank
    @field:Schema(example = "supersecret123")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank
    @field:Schema(description = "A refresh token previously issued by /login or /refresh")
    val refreshToken: String,
)

data class UserResponse(
    @field:Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    val id: UUID,

    @field:Schema(example = "jane.doe@example.com", nullable = true, description = "Null for phone-only (OTP-registered) accounts")
    val email: String?,

    @field:Schema(example = "+989123456789", nullable = true, description = "Null for email/password accounts that never verified a phone number")
    val phoneNumber: String?,

    @field:Schema(example = "Jane Doe")
    val fullName: String,

    @field:Schema(example = "CUSTOMER")
    val role: UserRole,
)

data class AuthResponse(
    val user: UserResponse,

    @field:Schema(description = "Bearer token for the Authorization header, short-lived")
    val accessToken: String,

    val accessTokenExpiresAt: Instant,

    @field:Schema(description = "Exchange at /api/v1/auth/refresh for a new token pair; long-lived")
    val refreshToken: String,

    val refreshTokenExpiresAt: Instant,
)
