package ai.rojan.backend.infrastructure.persistence.idempotency

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IdempotencyKeySpringDataRepository : JpaRepository<IdempotencyKeyJpaEntity, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): IdempotencyKeyJpaEntity?
}
