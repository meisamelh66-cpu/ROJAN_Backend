package ai.rojan.backend.api.auth

import ai.rojan.backend.application.auth.AuthenticateUserCommand
import ai.rojan.backend.application.auth.AuthenticateUserUseCase
import ai.rojan.backend.application.auth.AuthenticationResult
import ai.rojan.backend.application.auth.RefreshTokenCommand
import ai.rojan.backend.application.auth.RefreshTokenUseCase
import ai.rojan.backend.application.auth.RegisterUserCommand
import ai.rojan.backend.application.auth.RegisterUserUseCase
import ai.rojan.backend.domain.user.User
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController(
    private val registerUserUseCase: RegisterUserUseCase,
    private val authenticateUserUseCase: AuthenticateUserUseCase,
    private val refreshTokenUseCase: RefreshTokenUseCase,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new account")
    fun register(@Valid @RequestBody request: RegisterRequest): UserResponse {
        val user = registerUserUseCase.execute(
            RegisterUserCommand(
                email = request.email,
                rawPassword = request.password,
                fullName = request.fullName,
                role = request.role,
            ),
        )
        return user.toResponse()
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive an access/refresh token pair")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse {
        val result = authenticateUserUseCase.execute(
            AuthenticateUserCommand(email = request.email, rawPassword = request.password),
        )
        return result.toResponse()
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair")
    fun refresh(@Valid @RequestBody request: RefreshRequest): AuthResponse {
        val result = refreshTokenUseCase.execute(RefreshTokenCommand(request.refreshToken))
        return result.toResponse()
    }

    private fun User.toResponse() = UserResponse(
        id = id.value,
        email = email.value,
        fullName = fullName,
        role = role,
    )

    private fun AuthenticationResult.toResponse() = AuthResponse(
        user = user.toResponse(),
        accessToken = accessToken,
        accessTokenExpiresAt = accessTokenExpiresAt,
        refreshToken = refreshToken,
        refreshTokenExpiresAt = refreshTokenExpiresAt,
    )
}
