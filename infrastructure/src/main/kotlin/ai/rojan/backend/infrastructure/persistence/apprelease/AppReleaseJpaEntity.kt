package ai.rojan.backend.infrastructure.persistence.apprelease

import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Persistence model for [ai.rojan.backend.domain.apprelease.AppRelease]. Mirrors [ai.rojan.backend.infrastructure.persistence.banner.BannerJpaEntity]'s shape. `applicationId` is stored as the real Android application id string directly (not the [ai.rojan.backend.domain.apprelease.AppTarget] enum name) - it's already the natural external key everywhere else (public API path variable, admin UI, JSON responses). */
@Entity
@Table(name = "app_releases")
@EntityListeners(AuditingEntityListener::class)
class AppReleaseJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "application_id", nullable = false, length = 64)
    val applicationId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    val channel: AppReleaseChannel,

    @Column(name = "version_name", nullable = false, length = 32)
    var versionName: String,

    @Column(name = "version_code", nullable = false)
    val versionCode: Int,

    @Column(name = "min_supported_version_code", nullable = false)
    var minSupportedVersionCode: Int,

    @Column(name = "is_mandatory", nullable = false)
    var isMandatory: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: AppReleaseStatus,

    @Column(name = "download_url", nullable = false, length = 2000)
    var downloadUrl: String,

    @Column(nullable = false, length = 64)
    var sha256: String,

    @Column(name = "file_size_bytes", nullable = false)
    var fileSizeBytes: Long,

    @Column(name = "release_notes", columnDefinition = "text")
    var releaseNotes: String?,

    @Column(name = "release_date", nullable = false)
    var releaseDate: LocalDate,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean,

    @Column(name = "signer_subject", length = 512)
    var signerSubject: String?,

    @Column(name = "signer_thumbprint", length = 64)
    var signerThumbprint: String?,

    @Column(name = "published_at")
    var publishedAt: Instant?,

    @Column(name = "published_by")
    var publishedBy: UUID?,

    @Column(name = "created_by", nullable = false)
    val createdBy: UUID,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    /** Optimistic lock - null only before the first insert (which also tells Spring Data the entity is new). */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null
}
