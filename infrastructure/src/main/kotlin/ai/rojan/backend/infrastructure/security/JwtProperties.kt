package ai.rojan.backend.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rojan.security.jwt")
data class JwtProperties(
    /** HMAC-SHA signing secret. Must be at least 32 bytes. No default — startup fails loudly if unset. */
    val secret: String,
    val issuer: String = "rojan-ai-backend",
    val accessTokenTtlMinutes: Long = 15,
    val refreshTokenTtlDays: Long = 30,
)
