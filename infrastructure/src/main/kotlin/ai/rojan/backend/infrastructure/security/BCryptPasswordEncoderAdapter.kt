package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.application.port.PasswordEncoderPort
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

@Component
class BCryptPasswordEncoderAdapter : PasswordEncoderPort {

    private val delegate = BCryptPasswordEncoder(BCRYPT_STRENGTH)

    override fun encode(rawPassword: String): String = delegate.encode(rawPassword)

    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        delegate.matches(rawPassword, encodedPassword)

    private companion object {
        const val BCRYPT_STRENGTH = 12
    }
}
