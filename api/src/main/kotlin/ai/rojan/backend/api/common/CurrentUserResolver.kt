package ai.rojan.backend.api.common

import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component

/** Resolves the authenticated JWT principal (email) to a domain [UserId], shared across controllers. */
@Component
class CurrentUserResolver(
    private val userRepository: UserRepository,
) {
    fun resolve(principal: UserDetails): UserId {
        val user = userRepository.findByEmail(Email(principal.username))
            ?: throw UserNotFoundException(principal.username)
        return user.id
    }
}
