package ai.rojan.backend.application.auth

/**
 * Phase 1.1 Auth API Rate Limiting. Framework-free (same reasoning as
 * [OtpPolicy] - the application module has no Spring dependency;
 * `infrastructure.security.SecurityPropertiesConfig` builds one of these
 * from the Spring-bound `AuthRateLimitProperties`). Covers
 * `/auth/login`/`/auth/register`/`/auth/refresh` - one policy for all
 * three, same "one policy per auth sub-area" shape [OtpPolicy] already
 * uses for request/resend/verify. Defaults are reasonable starting
 * points, not empirically tuned - freely adjustable via
 * `AuthRateLimitProperties`' env vars without touching this class.
 */
data class AuthRateLimitPolicy(
    val loginLimitPerEmailWindow: Int,
    val loginLimitPerIpWindow: Int,
    val loginWindowSeconds: Long,
    val registerLimitPerIpWindow: Int,
    val registerWindowSeconds: Long,
    val refreshLimitPerIpWindow: Int,
    val refreshWindowSeconds: Long,
)
