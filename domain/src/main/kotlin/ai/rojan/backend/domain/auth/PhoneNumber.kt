package ai.rojan.backend.domain.auth

/** An E.164-format mobile number (e.g. `+989123456789`) — the identity anchor for OTP-based authentication, alongside [ai.rojan.backend.domain.user.Email] for password-based accounts. */
data class PhoneNumber(val value: String) {
    init {
        require(E164_REGEX.matches(value)) { "Invalid phone number: $value (expected E.164 format, e.g. +989123456789)" }
    }

    companion object {
        private val E164_REGEX = Regex("^\\+[1-9]\\d{7,14}$")
    }
}
