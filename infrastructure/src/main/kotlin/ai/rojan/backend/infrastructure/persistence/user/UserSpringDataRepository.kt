package ai.rojan.backend.infrastructure.persistence.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserSpringDataRepository : JpaRepository<UserJpaEntity, UUID> {
    fun findByEmail(email: String): UserJpaEntity?
    fun existsByEmail(email: String): Boolean

    /** Mobile-First Authentication Phase 1. */
    fun findByPhoneNumber(phoneNumber: String): UserJpaEntity?
    fun existsByPhoneNumber(phoneNumber: String): Boolean
}
