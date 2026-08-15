package ai.rojan.backend.application.auth

/**
 * The numeric OTP security policy, framework-free (the application module
 * has no Spring dependency — unlike `infrastructure.security.JwtProperties`,
 * this can't be `@ConfigurationProperties` itself; `api.config.UseCaseConfig`
 * builds one of these from the Spring-bound `OtpProperties` when wiring
 * `RequestOtpUseCase`/`VerifyOtpUseCase`). All values are the Mobile Auth
 * Architecture proposal's stated defaults — reasonable starting points, not
 * empirically tuned, freely adjustable via `OtpProperties`' env vars without
 * touching this class.
 */
data class OtpPolicy(
    val codeLength: Int,
    val ttlSeconds: Long,
    val maxAttempts: Int,
    val resendCooldownSeconds: Long,
    val requestLimitPerPhoneShortWindow: Int,
    val requestShortWindowSeconds: Long,
    val requestLimitPerPhoneLongWindow: Int,
    val requestLongWindowSeconds: Long,
    val requestLimitPerIpLongWindow: Int,
    val verifyLimitPerPhoneWindow: Int,
    val verifyWindowSeconds: Long,
)
