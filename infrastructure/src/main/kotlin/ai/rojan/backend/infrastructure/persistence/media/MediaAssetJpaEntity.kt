package ai.rojan.backend.infrastructure.persistence.media

import ai.rojan.backend.domain.media.MediaAssetStatus
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

/**
 * Persistence model for [ai.rojan.backend.domain.media.MediaAsset].
 * Deliberately separate from the domain entity so JPA/Hibernate concerns
 * never leak into the domain layer; [MediaAssetRepositoryAdapter] maps
 * between the two.
 *
 * [salonId] / [userId] (Phase 5A.2): exactly one is set, mirrored by
 * `chk_media_assets_exactly_one_owner` in `V24__user_profile_media.sql`.
 */
@Entity
@Table(name = "media_assets")
@EntityListeners(AuditingEntityListener::class)
class MediaAssetJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id")
    val salonId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 16)
    var mediaType: MediaType,

    @Column(name = "storage_key", nullable = false, length = 500)
    var storageKey: String,

    @Column(name = "original_name", nullable = false)
    val originalName: String,

    @Column(name = "mime_type", nullable = false, length = 100)
    val mimeType: String,

    @Column(name = "file_size", nullable = false)
    val fileSize: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: MediaAssetStatus,

    @Column(name = "uploaded_by", nullable = false)
    val uploadedBy: UUID,

    @Column(name = "target_id")
    var targetId: UUID? = null,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "user_id")
    val userId: UUID? = null,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
