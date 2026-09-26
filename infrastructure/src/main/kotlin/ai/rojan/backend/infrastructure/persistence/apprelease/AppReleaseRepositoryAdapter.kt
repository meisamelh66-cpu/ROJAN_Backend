package ai.rojan.backend.infrastructure.persistence.apprelease

import ai.rojan.backend.domain.apprelease.AppRelease
import ai.rojan.backend.domain.apprelease.AppReleaseId
import ai.rojan.backend.domain.apprelease.AppReleaseRepository
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import ai.rojan.backend.domain.apprelease.AppTarget
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class AppReleaseRepositoryAdapter(
    private val jpaRepository: AppReleaseSpringDataRepository,
) : AppReleaseRepository {

    override fun findById(id: AppReleaseId): AppRelease? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByTarget(target: AppTarget): List<AppRelease> =
        jpaRepository.findByApplicationId(target.applicationId).map { it.toDomain() }.sortedByDescending { it.versionCode }

    override fun findLatestPublished(target: AppTarget): AppRelease? =
        jpaRepository.findFirstByApplicationIdAndStatusAndIsActiveTrueOrderByVersionCodeDesc(
            target.applicationId,
            AppReleaseStatus.PUBLISHED,
        )?.toDomain()

    override fun existsByTargetAndVersionCode(target: AppTarget, versionCode: Int): Boolean =
        jpaRepository.existsByApplicationIdAndVersionCode(target.applicationId, versionCode)

    override fun save(release: AppRelease): AppRelease {
        val entity = jpaRepository.findById(release.id.value).orElse(null)
            ?.apply {
                versionName = release.versionName
                minSupportedVersionCode = release.minSupportedVersionCode
                isMandatory = release.isMandatory
                status = release.status
                downloadUrl = release.downloadUrl
                sha256 = release.sha256
                fileSizeBytes = release.fileSizeBytes
                releaseNotes = release.releaseNotes
                releaseDate = release.releaseDate
                isActive = release.isActive
            }
            ?: AppReleaseJpaEntity(
                id = release.id.value,
                applicationId = release.target.applicationId,
                versionName = release.versionName,
                versionCode = release.versionCode,
                minSupportedVersionCode = release.minSupportedVersionCode,
                isMandatory = release.isMandatory,
                status = release.status,
                downloadUrl = release.downloadUrl,
                sha256 = release.sha256,
                fileSizeBytes = release.fileSizeBytes,
                releaseNotes = release.releaseNotes,
                releaseDate = release.releaseDate,
                isActive = release.isActive,
                createdBy = release.createdBy.value,
            )
        return jpaRepository.save(entity).toDomain()
    }

    private fun AppReleaseJpaEntity.toDomain(): AppRelease = AppRelease.reconstitute(
        id = AppReleaseId(id),
        target = requireNotNull(AppTarget.fromApplicationId(applicationId)) {
            "Persisted app_releases row has an application_id ('$applicationId') outside the known AppTarget set - schema CHECK constraint should have prevented this"
        },
        versionName = versionName,
        versionCode = versionCode,
        minSupportedVersionCode = minSupportedVersionCode,
        isMandatory = isMandatory,
        status = status,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        fileSizeBytes = fileSizeBytes,
        releaseNotes = releaseNotes,
        releaseDate = releaseDate,
        isActive = isActive,
        createdBy = UserId(createdBy),
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
