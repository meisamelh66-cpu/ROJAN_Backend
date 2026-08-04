package ai.rojan.backend.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties

/** Mobile-First Authentication Phase 1. Every value is the Mobile Auth Architecture proposal's stated default — reasonable starting points, not empirically tuned; override any of them per-environment via the matching env var without touching code. */
@ConfigurationProperties(prefix = "rojan.security.otp")
data class OtpProperties(
    val ttlSeconds: Long = 120,
    val maxAttempts: Int = 5,
    val resendCooldownSeconds: Long = 60,
    val requestLimitPerPhoneShortWindow: Int = 3,
    val requestShortWindowSeconds: Long = 600,
    val requestLimitPerPhoneLongWindow: Int = 5,
    val requestLongWindowSeconds: Long = 3600,
    val requestLimitPerIpLongWindow: Int = 10,
    val verifyLimitPerPhoneWindow: Int = 10,
    val verifyWindowSeconds: Long = 600,
)
