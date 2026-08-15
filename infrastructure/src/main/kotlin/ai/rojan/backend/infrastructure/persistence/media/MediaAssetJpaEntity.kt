package ai.rojan.backend.infrastructure.persistence.media

import ai.rojan.backend.domain.media.MediaOwnerType
import ai.rojan.backend.domain.media.MediaType
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

/** Persistence model for [ai.rojan.backend.domain.media.MediaAsset] - deliberately separate from the domain entity, same reasoning as [ai.rojan.backend.infrastructure.persistence.salon.SalonJpaEntity]. */
@Entity
@Table(name = "media_assets")
@EntityListeners(AuditingEntityListener::class)
class MediaAssetJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 16)
    val ownerType: MediaOwnerType,

    @Column(name = "owner_id", nullable = false)
    val ownerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 16)
    val mediaType: MediaType,

    @Column(name = "storage_key", nullable = false, length = 500)
    val storageKey: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "mime_type", nullable = false, length = 100)
    val mimeType: String,

    @Column(name = "file_size", nullable = false)
    val fileSize: Long,

    @Column(nullable = false, length = 1000)
    val url: String,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
