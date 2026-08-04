package ai.rojan.backend.domain.user

import ai.rojan.backend.domain.auth.PhoneNumber

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

    /** Mobile-First Authentication Phase 1. */
    fun findByPhoneNumber(phoneNumber: PhoneNumber): User?

    fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean
}
