package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User as SpringUser
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Bridges the domain [UserRepository] port to Spring Security's
 * [UserDetailsService] SPI.
 *
 * Mobile-First Authentication Phase 1: [username] is now the user's ID
 * (stringified UUID), not their email — a phone-only account has no email
 * at all, so email can no longer be the universal identity key request-time
 * authentication resolves by. [JwtAuthenticationFilter] passes the JWT's
 * `sub` claim here; [ai.rojan.backend.api.common.CurrentUserResolver]
 * parses the same value back out. Email/phone remain the *login-time* keys
 * (`/auth/login` looks up by email, `/auth/otp/verify` by phone) — this
 * class is never involved at login time, only on every subsequent
 * authenticated request.
 */
@Service
class RojanUserDetailsService(
    private val userRepository: UserRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val userId = runCatching { UUID.fromString(username) }.getOrNull()
            ?: throw UsernameNotFoundException("Not a valid user id: $username")
        val user = userRepository.findById(UserId(userId))
            ?: throw UsernameNotFoundException("No user found for id: $username")

        return SpringUser
            .withUsername(user.id.value.toString())
            // A phone-only account has no password at all; this value is never
            // actually compared against anything on this path (password
            // verification only happens in AuthenticateUserUseCase, against
            // UserRepository.findByEmail's own result, not through this service).
            .password(user.passwordHash ?: "")
            .authorities(SimpleGrantedAuthority("ROLE_${user.role.name}"))
            .disabled(!user.active)
            .build()
    }
}
