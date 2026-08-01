package ai.rojan.backend.api.auth

import ai.rojan.backend.domain.user.UserRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank
    @field:Email
    val email: String,

    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val password: String,

    @field:NotBlank
    @field:Size(max = 255)
    val fullName: String,

    @field:NotNull
    val role: UserRole,
)

data class LoginRequest(
    @field:NotBlank
    @field:Email
    val email: String,

    @field:NotBlank
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val fullName: String,
    val role: UserRole,
)

data class AuthResponse(
    val user: UserResponse,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
