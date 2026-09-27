package ai.rojan.backend.infrastructure.persistence.apprelease

import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppReleaseSpringDataRepository : JpaRepository<AppReleaseJpaEntity, UUID> {
    fun findByApplicationId(applicationId: String): List<AppReleaseJpaEntity>

    fun existsByApplicationIdAndChannelAndVersionCode(applicationId: String, channel: AppReleaseChannel, versionCode: Int): Boolean

    /** The one query the public "check for update" endpoint needs: the highest versionCode among an app's currently published+active releases on one channel. */
    fun findFirstByApplicationIdAndChannelAndStatusAndIsActiveTrueOrderByVersionCodeDesc(
        applicationId: String,
        channel: AppReleaseChannel,
        status: AppReleaseStatus,
    ): AppReleaseJpaEntity?
}
