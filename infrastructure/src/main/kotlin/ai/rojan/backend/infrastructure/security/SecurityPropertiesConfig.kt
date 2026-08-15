package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.application.auth.AuthRateLimitPolicy
import ai.rojan.backend.application.auth.OtpPolicy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(JwtProperties::class, OtpProperties::class, AuthRateLimitProperties::class)
class SecurityPropertiesConfig {

    /**
     * Bridges the Spring-bound [OtpProperties] into the framework-free
     * [OtpPolicy] the `application` module's OTP use cases actually depend
     * on — mirrors how `api.config.UseCaseConfig` wires those use cases,
     * but lives here (not there) because `api` has no compile-time
     * dependency on `infrastructure` (see that module's `build.gradle.kts`);
     * only `infrastructure` can see [OtpProperties].
     */
    @Bean
    fun otpPolicy(properties: OtpProperties) = OtpPolicy(
        codeLength = properties.codeLength,
        ttlSeconds = properties.ttlSeconds,
        maxAttempts = properties.maxAttempts,
        resendCooldownSeconds = properties.resendCooldownSeconds,
        requestLimitPerPhoneShortWindow = properties.requestLimitPerPhoneShortWindow,
        requestShortWindowSeconds = properties.requestShortWindowSeconds,
        requestLimitPerPhoneLongWindow = properties.requestLimitPerPhoneLongWindow,
        requestLongWindowSeconds = properties.requestLongWindowSeconds,
        requestLimitPerIpLongWindow = properties.requestLimitPerIpLongWindow,
        verifyLimitPerPhoneWindow = properties.verifyLimitPerPhoneWindow,
        verifyWindowSeconds = properties.verifyWindowSeconds,
    )

    /** Bridges [AuthRateLimitProperties] into [AuthRateLimitPolicy] - same reasoning as [otpPolicy] above. */
    @Bean
    fun authRateLimitPolicy(properties: AuthRateLimitProperties) = AuthRateLimitPolicy(
        loginLimitPerEmailWindow = properties.loginLimitPerEmailWindow,
        loginLimitPerIpWindow = properties.loginLimitPerIpWindow,
        loginWindowSeconds = properties.loginWindowSeconds,
        registerLimitPerIpWindow = properties.registerLimitPerIpWindow,
        registerWindowSeconds = properties.registerWindowSeconds,
        refreshLimitPerIpWindow = properties.refreshLimitPerIpWindow,
        refreshWindowSeconds = properties.refreshWindowSeconds,
    )
}
