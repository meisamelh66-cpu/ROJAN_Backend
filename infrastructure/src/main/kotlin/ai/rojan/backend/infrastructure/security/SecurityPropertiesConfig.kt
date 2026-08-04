package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.application.auth.OtpPolicy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(JwtProperties::class, OtpProperties::class)
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
}
