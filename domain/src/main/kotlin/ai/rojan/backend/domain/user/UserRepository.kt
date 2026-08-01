package ai.rojan.backend.domain.user

/**
 * Output port for user persistence. Implemented by an adapter in the
 * infrastructure module (repository pattern) — the domain layer never
 * depends on a persistence framework.
 */
interface UserRepository {
    fun save(user: User): User
    fun findById(id: UserId): User?
    fun findByEmail(email: Email): User?
    fun existsByEmail(email: Email): Boolean
}
