package ai.rojan.backend.api.auth

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.application.auth.AuthenticateUserCommand
import ai.rojan.backend.application.auth.AuthenticateUserUseCase
import ai.rojan.backend.application.auth.AuthenticationResult
import ai.rojan.backend.application.auth.LogoutCommand
import ai.rojan.backend.application.auth.LogoutUseCase
import ai.rojan.backend.application.auth.RefreshTokenCommand
import ai.rojan.backend.application.auth.RefreshTokenUseCase
import ai.rojan.backend.application.auth.RegisterUserCommand
import ai.rojan.backend.application.auth.RegisterUserUseCase
import ai.rojan.backend.application.auth.OtpIssuedResult
import ai.rojan.backend.application.auth.RequestOtpCommand
import ai.rojan.backend.application.auth.RequestOtpUseCase
import ai.rojan.backend.application.auth.VerifyOtpCommand
import ai.rojan.backend.application.auth.VerifyOtpUseCase
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
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
    private val requestOtpUseCase: RequestOtpUseCase,
    private val verifyOtpUseCase: VerifyOtpUseCase,
    private val logoutUseCase: LogoutUseCase,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new account")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Account created"),
        ApiResponse(
            responseCode = "400",
            description = "Validation failed (weak password, invalid email, etc.)",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "An account with this email already exists",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many registration attempts from this caller IP",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun register(@Valid @RequestBody request: RegisterRequest, httpRequest: HttpServletRequest): UserResponse {
        // Platform Authority (Phase 5): a real, pre-existing gap closed here - RegisterUserCommand.role
        // was passed straight from client input with no restriction, which meant self-registering as
        // PLATFORM_ADMIN/PLATFORM_REVIEWER through this public endpoint was possible before this check
        // existed (role carried no real authority until this phase). Every legitimate platform role
        // account is created exclusively through CreatePlatformReviewerUseCase (PLATFORM_ADMIN-only,
        // never self-service) - see Section 11's own "never taken from caller input" requirement.
        require(request.role !in PLATFORM_ROLES) { "role must be one of CUSTOMER, MANAGER, SPECIALIST" }
        val user = registerUserUseCase.execute(
            RegisterUserCommand(
                email = request.email,
                rawPassword = request.password,
                fullName = request.fullName,
                role = request.role,
                callerIp = httpRequest.remoteAddr,
            ),
        )
        return user.toResponse()
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive an access/refresh token pair")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Authenticated"),
        ApiResponse(
            responseCode = "401",
            description = "Invalid email or password",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many login attempts for this email or caller IP",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun login(@Valid @RequestBody request: LoginRequest, httpRequest: HttpServletRequest): AuthResponse {
        val result = authenticateUserUseCase.execute(
            AuthenticateUserCommand(email = request.email, rawPassword = request.password, callerIp = httpRequest.remoteAddr),
        )
        return result.toResponse()
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "New token pair issued"),
        ApiResponse(
            responseCode = "401",
            description = "Token is invalid, expired, or not a refresh token",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many token refresh attempts from this caller IP",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun refresh(@Valid @RequestBody request: RefreshRequest, httpRequest: HttpServletRequest): AuthResponse {
        val result = refreshTokenUseCase.execute(RefreshTokenCommand(request.refreshToken, callerIp = httpRequest.remoteAddr))
        return result.toResponse()
    }

    @PostMapping("/otp/request")
    @Operation(summary = "Request an OTP code for a mobile number")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Code issued (sent via SMS)"),
        ApiResponse(
            responseCode = "400",
            description = "phoneNumber is not valid E.164",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many OTP requests for this phone number or caller IP",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun requestOtp(@Valid @RequestBody request: OtpRequestRequest, httpRequest: HttpServletRequest): OtpIssuedResponse {
        val result = requestOtpUseCase.execute(RequestOtpCommand(phoneNumber = request.phoneNumber, callerIp = httpRequest.remoteAddr))
        return result.toResponse()
    }

    @PostMapping("/otp/resend")
    @Operation(summary = "Resend an OTP code for a mobile number, subject to the same rate limits as /otp/request")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Code re-issued (sent via SMS)"),
        ApiResponse(
            responseCode = "400",
            description = "phoneNumber is not valid E.164",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many OTP requests for this phone number or caller IP",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun resendOtp(@Valid @RequestBody request: OtpResendRequest, httpRequest: HttpServletRequest): OtpIssuedResponse {
        val result = requestOtpUseCase.execute(RequestOtpCommand(phoneNumber = request.phoneNumber, callerIp = httpRequest.remoteAddr))
        return result.toResponse()
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Verify an OTP code and receive an access/refresh token pair; creates the account on first successful verification")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Authenticated"),
        ApiResponse(
            responseCode = "401",
            description = "Code is invalid, expired, or has no matching request",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many verification attempts for this phone number",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun verifyOtp(@Valid @RequestBody request: OtpVerifyRequest): AuthResponse {
        val result = verifyOtpUseCase.execute(
            VerifyOtpCommand(phoneNumber = request.phoneNumber, code = request.code, fullName = request.fullName),
        )
        return result.toResponse()
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token's session - it and every other token in the same refresh-token family become invalid immediately")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Session revoked (or the token was already invalid - logout is idempotent either way)"),
    )
    fun logout(@Valid @RequestBody request: RefreshRequest) {
        logoutUseCase.execute(LogoutCommand(request.refreshToken))
    }

    private fun User.toResponse() = UserResponse(
        id = id.value,
        email = email?.value,
        phoneNumber = phoneNumber?.value,
        fullName = fullName,
        role = role,
    )

    private fun OtpIssuedResult.toResponse() = OtpIssuedResponse(
        phoneNumber = phoneNumber,
        expiresInSeconds = expiresInSeconds,
        canResendAfterSeconds = canResendAfterSeconds,
    )

    private fun AuthenticationResult.toResponse() = AuthResponse(
        user = user.toResponse(),
        accessToken = accessToken,
        accessTokenExpiresAt = accessTokenExpiresAt,
        refreshToken = refreshToken,
        refreshTokenExpiresAt = refreshTokenExpiresAt,
    )

    private companion object {
        val PLATFORM_ROLES = setOf(UserRole.PLATFORM_ADMIN, UserRole.PLATFORM_REVIEWER)
    }
}
