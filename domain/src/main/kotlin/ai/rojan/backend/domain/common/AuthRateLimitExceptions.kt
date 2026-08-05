package ai.rojan.backend.domain.common

/** The per-email or per-IP `/auth/login` rate limit was exceeded — same "consume every applicable budget" reasoning as [OtpRateLimitExceededException]. */
class LoginRateLimitExceededException(email: String) :
    DomainException("Too many login attempts for: $email")

/** The per-IP `/auth/register` rate limit was exceeded. */
class RegisterRateLimitExceededException(callerIp: String) :
    DomainException("Too many registration attempts from: $callerIp")

/** The per-IP `/auth/refresh` rate limit was exceeded. */
class RefreshRateLimitExceededException(callerIp: String) :
    DomainException("Too many token refresh attempts from: $callerIp")
