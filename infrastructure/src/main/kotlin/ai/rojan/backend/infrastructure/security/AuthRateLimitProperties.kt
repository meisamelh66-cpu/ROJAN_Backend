package ai.rojan.backend.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties

/** Phase 1.1 Auth API Rate Limiting. Every value is a reasonable starting point, not empirically tuned; override any of them per-environment via the matching env var without touching code - same "config, not code" convention as [OtpProperties]. */
@ConfigurationProperties(prefix = "rojan.security.auth")
data class AuthRateLimitProperties(
    val loginLimitPerEmailWindow: Int = 5,
    val loginLimitPerIpWindow: Int = 20,
    val loginWindowSeconds: Long = 300,
    val registerLimitPerIpWindow: Int = 5,
    val registerWindowSeconds: Long = 3600,
    val refreshLimitPerIpWindow: Int = 30,
    val refreshWindowSeconds: Long = 300,
)
