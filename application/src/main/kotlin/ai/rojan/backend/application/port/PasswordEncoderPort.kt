package ai.rojan.backend.application.port

/**
 * Output port for password hashing. Implemented in infrastructure using a
 * concrete algorithm (BCrypt) so the application layer stays framework-free.
 */
interface PasswordEncoderPort {
    fun encode(rawPassword: String): String
    fun matches(rawPassword: String, encodedPassword: String): Boolean
}
