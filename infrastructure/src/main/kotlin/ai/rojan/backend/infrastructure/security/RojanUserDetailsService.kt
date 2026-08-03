package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User as SpringUser
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/** Bridges the domain [UserRepository] port to Spring Security's [UserDetailsService] SPI. */
@Service
class RojanUserDetailsService(
    private val userRepository: UserRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findByEmail(Email(username))
            ?: throw UsernameNotFoundException("No user found for email: $username")

        return SpringUser
            .withUsername(user.email.value)
            .password(user.passwordHash)
            .authorities(SimpleGrantedAuthority("ROLE_${user.role.name}"))
            .disabled(!user.active)
            .build()
    }
}
