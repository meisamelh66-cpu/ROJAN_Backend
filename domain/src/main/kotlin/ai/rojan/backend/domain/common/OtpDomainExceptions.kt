package ai.rojan.backend.domain.common

/** Wrong code, expired code, or no active code at all for the phone — deliberately one exception for all three, so a caller can't distinguish "never requested" from "guessed wrong" (avoids a minor enumeration/timing signal). */
class InvalidOtpException(phoneNumber: String) :
    DomainException("Invalid or expired OTP for: $phoneNumber")

/** The per-phone or per-IP OTP request/resend rate limit was exceeded. */
class OtpRateLimitExceededException(phoneNumber: String) :
    DomainException("Too many OTP requests for: $phoneNumber")

/** The per-phone OTP *verify* rate limit was exceeded — distinct from a single code's own attempt counter (see OneTimePassword.attemptsRemaining), this guards the /verify endpoint itself against rapid guessing across multiple issued codes. */
class OtpVerifyRateLimitExceededException(phoneNumber: String) :
    DomainException("Too many OTP verification attempts for: $phoneNumber")
