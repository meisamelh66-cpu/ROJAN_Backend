package ai.rojan.backend.api.common

import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves the authenticated JWT principal to a domain [UserId], shared
 * across controllers.
 *
 * Mobile-First Authentication Phase 1: [principal]'s username is now the
 * user's ID directly (see `RojanUserDetailsService`'s own doc comment for
 * why email can no longer be the universal request-time key — a phone-only
 * account has none). The [UserRepository] lookup is kept — not just a
 * `UUID.fromString` — deliberately, to preserve the existing safety
 * property that a request is rejected if the user was deleted after their
 * token was issued, not silently trusted from stale claims alone.
 */
@Component
class CurrentUserResolver(
    private val userRepository: UserRepository,
) {
    fun resolve(principal: UserDetails): UserId {
        val userId = UserId(UUID.fromString(principal.username))
        userRepository.findById(userId) ?: throw UserNotFoundException(principal.username)
        return userId
    }
}
