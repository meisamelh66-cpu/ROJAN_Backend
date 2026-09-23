package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.ExtensionTitleType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.salon.SalonInternalExtension].
 * No `updated_at` column exists (V26) - these rows are add/remove only, never
 * edited in place, so no [org.springframework.data.annotation.LastModifiedDate]
 * field is declared here either.
 */
@Entity
@Table(name = "salon_internal_extensions")
@EntityListeners(AuditingEntityListener::class)
class SalonInternalExtensionJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "title_type", nullable = false, length = 20)
    var titleType: ExtensionTitleType,

    @Column(name = "title", nullable = true, length = 120)
    var title: String?,

    @Column(name = "extension_number", nullable = false, length = 16)
    var extensionNumber: String,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
