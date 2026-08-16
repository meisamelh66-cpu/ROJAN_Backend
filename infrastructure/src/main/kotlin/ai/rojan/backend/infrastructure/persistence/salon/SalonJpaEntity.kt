package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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

    @Column(nullable = false, length = 80)
    var slug: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_status", nullable = false, length = 16)
    var onboardingStatus: SalonOnboardingStatus,

    @Column(name = "logo_media_id", nullable = true)
    var logoMediaId: UUID?,

    @Column(name = "cover_media_id", nullable = true)
    var coverMediaId: UUID?,

    @Column(nullable = true)
    var latitude: Double?,

    @Column(nullable = true)
    var longitude: Double?,

    @Column(nullable = false)
    var active: Boolean,

    @Column(name = "logo_media_id", nullable = true)
    var logoMediaId: UUID?,

    @Column(name = "cover_media_id", nullable = true)
    var coverMediaId: UUID?,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
