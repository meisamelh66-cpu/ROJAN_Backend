package ai.rojan.backend.api.config

import ai.rojan.backend.application.auth.ActivateReviewerAccessUseCase
import ai.rojan.backend.application.auth.AuthRateLimitPolicy
import ai.rojan.backend.application.auth.AuthenticateUserUseCase
import ai.rojan.backend.application.auth.DeactivateReviewerAccessUseCase
import ai.rojan.backend.application.auth.LogoutUseCase
import ai.rojan.backend.application.auth.OtpPolicy
import ai.rojan.backend.application.auth.RefreshTokenUseCase
import ai.rojan.backend.application.auth.RegisterUserUseCase
import ai.rojan.backend.application.auth.RequestOtpUseCase
import ai.rojan.backend.application.auth.TryReviewerAccessLoginUseCase
import ai.rojan.backend.application.auth.VerifyOtpUseCase
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.RefreshTokenStorePort
import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.ReviewerAccessRepository
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
        rateLimiter: RateLimiterPort,
        authRateLimitPolicy: AuthRateLimitPolicy,
    ) = RegisterUserUseCase(userRepository, passwordEncoder, rateLimiter, authRateLimitPolicy)

    @Bean
    fun authenticateUserUseCase(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoderPort,
        tokenProvider: TokenProviderPort,
        rateLimiter: RateLimiterPort,
        authRateLimitPolicy: AuthRateLimitPolicy,
        refreshTokenStore: RefreshTokenStorePort,
    ) = AuthenticateUserUseCase(userRepository, passwordEncoder, tokenProvider, rateLimiter, authRateLimitPolicy, refreshTokenStore)

    @Bean
    fun refreshTokenUseCase(
        userRepository: UserRepository,
        tokenProvider: TokenProviderPort,
        rateLimiter: RateLimiterPort,
        authRateLimitPolicy: AuthRateLimitPolicy,
        refreshTokenStore: RefreshTokenStorePort,
    ) = RefreshTokenUseCase(userRepository, tokenProvider, rateLimiter, authRateLimitPolicy, refreshTokenStore)

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
        refreshTokenStore: RefreshTokenStorePort,
    ) = VerifyOtpUseCase(otpRepository, userRepository, tokenProvider, rateLimiter, otpPolicy, refreshTokenStore)

    @Bean
    fun logoutUseCase(
        tokenProvider: TokenProviderPort,
        refreshTokenStore: RefreshTokenStorePort,
    ) = LogoutUseCase(tokenProvider, refreshTokenStore)

    // Cafe Bazaar reviewer access — isolated, additive beans only. None of
    // these replace or wrap any bean above; verifyOtpUseCase's own bean
    // definition is untouched. platformAuthorization is the existing
    // PlatformAuthorizationResolver bean (defined in PlatformAuthorityUseCaseConfig),
    // reused as-is, not redefined here.

    @Bean
    fun activateReviewerAccessUseCase(
        reviewerAccessRepository: ReviewerAccessRepository,
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ActivateReviewerAccessUseCase(reviewerAccessRepository, userRepository, platformAuthorization)

    @Bean
    fun deactivateReviewerAccessUseCase(
        reviewerAccessRepository: ReviewerAccessRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = DeactivateReviewerAccessUseCase(reviewerAccessRepository, platformAuthorization)

    /** Same rateLimiter/otpPolicy beans [verifyOtpUseCase] above receives — the exact same shared instances, not a second configuration of either. */
    @Bean
    fun tryReviewerAccessLoginUseCase(
        reviewerAccessRepository: ReviewerAccessRepository,
        userRepository: UserRepository,
        tokenProvider: TokenProviderPort,
        refreshTokenStore: RefreshTokenStorePort,
        rateLimiter: RateLimiterPort,
        otpPolicy: OtpPolicy,
    ) = TryReviewerAccessLoginUseCase(reviewerAccessRepository, userRepository, tokenProvider, refreshTokenStore, rateLimiter, otpPolicy)
}
