package ai.rojan.backend.infrastructure.persistence.apprelease

import ai.rojan.backend.domain.apprelease.AppRelease
import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import ai.rojan.backend.domain.apprelease.AppReleaseId
import ai.rojan.backend.domain.apprelease.AppReleaseRepository
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import ai.rojan.backend.domain.apprelease.AppTarget
import ai.rojan.backend.domain.common.AppReleaseConcurrentModificationException
import ai.rojan.backend.domain.user.UserId
import org.springframework.orm.ObjectOptimisticLockingFailureException
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

    override fun findLatestPublished(target: AppTarget, channel: AppReleaseChannel): AppRelease? =
        jpaRepository.findFirstByApplicationIdAndChannelAndStatusAndIsActiveTrueOrderByVersionCodeDesc(
            target.applicationId,
            channel,
            AppReleaseStatus.PUBLISHED,
        )?.toDomain()

    override fun existsByTargetAndChannelAndVersionCode(target: AppTarget, channel: AppReleaseChannel, versionCode: Int): Boolean =
        jpaRepository.existsByApplicationIdAndChannelAndVersionCode(target.applicationId, channel, versionCode)

    /**
     * Optimistic concurrency: [release] carries the version of the row it was loaded from. If the
     * stored row has moved on since (another request saved it in between - e.g. published it), the
     * save is refused with [AppReleaseConcurrentModificationException] instead of copying stale
     * fields over the newer row. The version is also set on the merged entity, so the `@Version`
     * check in the final UPDATE catches a change that commits between this check and the write.
     */
    override fun save(release: AppRelease): AppRelease {
        val entity = jpaRepository.findById(release.id.value).orElse(null)
            ?.apply {
                if (version != release.version) {
                    throw AppReleaseConcurrentModificationException(release.id.value.toString())
                }
                version = release.version
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
                signerSubject = release.signerSubject
                signerThumbprint = release.signerThumbprint
                publishedAt = release.publishedAt
                publishedBy = release.publishedBy?.value
            }
            ?: AppReleaseJpaEntity(
                id = release.id.value,
                applicationId = release.target.applicationId,
                channel = release.channel,
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
                signerSubject = release.signerSubject,
                signerThumbprint = release.signerThumbprint,
                publishedAt = release.publishedAt,
                publishedBy = release.publishedBy?.value,
                createdBy = release.createdBy.value,
            )
        return try {
            jpaRepository.saveAndFlush(entity).toDomain()
        } catch (ex: ObjectOptimisticLockingFailureException) {
            throw AppReleaseConcurrentModificationException(release.id.value.toString())
        }
    }

    private fun AppReleaseJpaEntity.toDomain(): AppRelease = AppRelease.reconstitute(
        id = AppReleaseId(id),
        target = requireNotNull(AppTarget.fromApplicationId(applicationId)) {
            "Persisted app_releases row has an application_id ('$applicationId') outside the known AppTarget set - schema CHECK constraint should have prevented this"
        },
        channel = channel,
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
        signerSubject = signerSubject,
        signerThumbprint = signerThumbprint,
        publishedAt = publishedAt,
        publishedBy = publishedBy?.let(::UserId),
        createdBy = UserId(createdBy),
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
        version = version,
    )
}
