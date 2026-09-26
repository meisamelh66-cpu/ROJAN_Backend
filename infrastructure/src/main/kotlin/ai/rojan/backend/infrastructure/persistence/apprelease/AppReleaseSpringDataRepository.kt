package ai.rojan.backend.infrastructure.persistence.apprelease

import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppReleaseSpringDataRepository : JpaRepository<AppReleaseJpaEntity, UUID> {
    fun findByApplicationId(applicationId: String): List<AppReleaseJpaEntity>

    fun existsByApplicationIdAndVersionCode(applicationId: String, versionCode: Int): Boolean

    /** The one query the public "check for update" endpoint needs: the highest versionCode among an app's currently published+active releases. */
    fun findFirstByApplicationIdAndStatusAndIsActiveTrueOrderByVersionCodeDesc(
        applicationId: String,
        status: AppReleaseStatus,
    ): AppReleaseJpaEntity?
}
