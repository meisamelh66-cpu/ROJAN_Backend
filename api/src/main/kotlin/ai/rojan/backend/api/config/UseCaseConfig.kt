package ai.rojan.backend.api.config

import ai.rojan.backend.application.auth.AuthenticateUserUseCase
import ai.rojan.backend.application.auth.OtpPolicy
import ai.rojan.backend.application.auth.RefreshTokenUseCase
import ai.rojan.backend.application.auth.RegisterUserUseCase
import ai.rojan.backend.application.auth.RequestOtpUseCase
import ai.rojan.backend.application.auth.VerifyOtpUseCase
import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free application use cases as Spring beans. Kept in the
 * web/adapter layer rather than the application module itself, so
 * `application` stays free of any Spring dependency.
 */
@Configuration
class UseCaseConfig {

    @Bean
    fun registerUserUseCase(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoderPort,
    ) = RegisterUserUseCase(userRepository, passwordEncoder)

    @Bean
    fun authenticateUserUseCase(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoderPort,
        tokenProvider: TokenProviderPort,
    ) = AuthenticateUserUseCase(userRepository, passwordEncoder, tokenProvider)

    @Bean
    fun refreshTokenUseCase(
        userRepository: UserRepository,
        tokenProvider: TokenProviderPort,
    ) = RefreshTokenUseCase(userRepository, tokenProvider)

    @Bean
    fun requestOtpUseCase(
        otpRepository: OtpRepository,
        smsProvider: SmsProviderPort,
        rateLimiter: RateLimiterPort,
        otpPolicy: OtpPolicy,
    ) = RequestOtpUseCase(otpRepository, smsProvider, rateLimiter, otpPolicy)

    @Bean
    fun verifyOtpUseCase(
        otpRepository: OtpRepository,
        userRepository: UserRepository,
        tokenProvider: TokenProviderPort,
        rateLimiter: RateLimiterPort,
        otpPolicy: OtpPolicy,
    ) = VerifyOtpUseCase(otpRepository, userRepository, tokenProvider, rateLimiter, otpPolicy)
}
