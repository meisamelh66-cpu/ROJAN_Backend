package ai.rojan.backend.domain.apprelease

interface AppReleaseRepository {
    fun findById(id: AppReleaseId): AppRelease?

    /** Every release for one app, admin view - every channel, includes DRAFT/ARCHIVED/inactive rows, newest versionCode first. */
    fun findByTarget(target: AppTarget): List<AppRelease>

    /** Public read surface - the single highest-versionCode PUBLISHED+active release for one app on one channel, or null if none exists yet. */
    fun findLatestPublished(target: AppTarget, channel: AppReleaseChannel): AppRelease?

    fun existsByTargetAndChannelAndVersionCode(target: AppTarget, channel: AppReleaseChannel, versionCode: Int): Boolean

    fun save(release: AppRelease): AppRelease
}
