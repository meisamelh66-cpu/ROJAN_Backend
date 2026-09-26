package ai.rojan.backend.domain.apprelease

interface AppReleaseRepository {
    fun findById(id: AppReleaseId): AppRelease?

    /** Every release for one app, admin view - includes DRAFT/ARCHIVED/inactive rows, newest versionCode first. */
    fun findByTarget(target: AppTarget): List<AppRelease>

    /** Public read surface - the single highest-versionCode PUBLISHED+active release for one app, or null if none exists yet. */
    fun findLatestPublished(target: AppTarget): AppRelease?

    fun existsByTargetAndVersionCode(target: AppTarget, versionCode: Int): Boolean

    fun save(release: AppRelease): AppRelease
}
