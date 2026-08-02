package ai.rojan.backend.infrastructure.persistence.salon

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.salon.Salon]. Deliberately
 * separate from the domain entity so JPA/Hibernate concerns never leak into
 * the domain layer; [SalonRepositoryAdapter] maps between the two.
 */
@Entity
@Table(name = "salons")
@EntityListeners(AuditingEntityListener::class)
class SalonJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "owner_id", nullable = false)
    var ownerId: UUID,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = true)
    var description: String?,

    @Column(nullable = false, length = 32)
    var phone: String,

    @Column(nullable = true)
    var email: String?,

    @Column(nullable = false)
    var address: String,

    @Column(nullable = false)
    var active: Boolean,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
