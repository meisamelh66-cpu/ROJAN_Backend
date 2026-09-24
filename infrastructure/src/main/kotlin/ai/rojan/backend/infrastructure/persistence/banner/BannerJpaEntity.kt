package ai.rojan.backend.infrastructure.persistence.banner

import ai.rojan.backend.domain.banner.BannerTarget
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

/** Persistence model for [ai.rojan.backend.domain.banner.Banner]. Mirrors [ai.rojan.backend.infrastructure.persistence.media.MediaAssetJpaEntity]'s shape. */
@Entity
@Table(name = "banners")
@EntityListeners(AuditingEntityListener::class)
class BannerJpaEntity(
    @Id
    val id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val target: BannerTarget,

    @Column
    var title: String?,

    @Column
    var subtitle: String?,

    @Column
    var href: String?,

    @Column(name = "storage_key", nullable = false, length = 500)
    var storageKey: String,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int,

    @Column(name = "created_by", nullable = false)
    val createdBy: UUID,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
