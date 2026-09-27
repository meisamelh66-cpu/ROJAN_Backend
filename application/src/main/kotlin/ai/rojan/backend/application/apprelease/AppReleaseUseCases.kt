package ai.rojan.backend.application.apprelease

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.domain.apprelease.AppRelease
import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import ai.rojan.backend.domain.apprelease.AppReleaseId
import ai.rojan.backend.domain.apprelease.AppReleaseRepository
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import ai.rojan.backend.domain.apprelease.AppTarget
import ai.rojan.backend.domain.common.AppReleaseNotFoundException
import ai.rojan.backend.domain.common.AppReleaseVersionCodeAlreadyExistsException
import ai.rojan.backend.domain.common.InvalidApplicationIdException
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDate

/** Shared by every admin use case below - the one place an incoming, client-supplied `applicationId` string is validated against the known [AppTarget] set. Never silently falls back to a default app; an unrecognized id is always a hard [InvalidApplicationIdException], mirroring how [ai.rojan.backend.api.banner.BannerController]'s `target` binding fails closed for an unknown value. */
internal fun resolveTarget(applicationId: String): AppTarget =
    AppTarget.fromApplicationId(applicationId) ?: throw InvalidApplicationIdException(applicationId)

data class CreateAppReleaseCommand(
    val callerId: UserId,
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val minSupportedVersionCode: Int,
    val isMandatory: Boolean,
    val status: AppReleaseStatus,
    val downloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val releaseNotes: String?,
    val releaseDate: LocalDate,
    val isActive: Boolean,
    val channel: AppReleaseChannel = AppReleaseChannel.PRODUCTION,
    val signerSubject: String? = null,
    val signerThumbprint: String? = null,
)

/** PLATFORM_ADMIN only. `versionCode` must be unique per app and channel - a duplicate is a real [AppReleaseVersionCodeAlreadyExistsException], never a silent overwrite of the existing row. */
class CreateAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: CreateAppReleaseCommand): AppRelease {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val target = resolveTarget(command.applicationId)

        if (appReleaseRepository.existsByTargetAndChannelAndVersionCode(target, command.channel, command.versionCode)) {
            throw AppReleaseVersionCodeAlreadyExistsException(command.applicationId, command.channel.name, command.versionCode)
        }

        val release = AppRelease.create(
            target = target,
            versionName = command.versionName,
            versionCode = command.versionCode,
            minSupportedVersionCode = command.minSupportedVersionCode,
            isMandatory = command.isMandatory,
            status = command.status,
            downloadUrl = command.downloadUrl,
            sha256 = command.sha256,
            fileSizeBytes = command.fileSizeBytes,
            releaseNotes = command.releaseNotes,
            releaseDate = command.releaseDate,
            isActive = command.isActive,
            createdBy = command.callerId,
            channel = command.channel,
            signerSubject = command.signerSubject,
            signerThumbprint = command.signerThumbprint,
        )
        return appReleaseRepository.save(release)
    }
}

data class UpdateAppReleaseCommand(
    val callerId: UserId,
    val releaseId: AppReleaseId,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val isMandatory: Boolean,
    val status: AppReleaseStatus,
    val downloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val releaseNotes: String?,
    val releaseDate: LocalDate,
    /** Null means "leave unchanged" - see [AppRelease.updateMetadata]. */
    val signerSubject: String? = null,
    /** Null means "leave unchanged" - see [AppRelease.updateMetadata]. */
    val signerThumbprint: String? = null,
)

/**
 * PLATFORM_ADMIN only. Never touches `applicationId`/`channel`/`versionCode` - see [AppRelease]'s own
 * doc comment for why those stay immutable once created. [UpdateAppReleaseCommand.status] may only
 * request a transition [AppRelease.transitionTo] allows (DRAFT -> PUBLISHED, PUBLISHED -> ARCHIVED,
 * or no change); re-publishing an ARCHIVED release is [RepublishAppReleaseUseCase]. The status is
 * checked before anything else changes, and the metadata edit runs before a publish so a DRAFT's
 * final artifact can be set and published in one save - after which it is locked.
 */
class UpdateAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: UpdateAppReleaseCommand): AppRelease {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val release = appReleaseRepository.findById(command.releaseId)
            ?: throw AppReleaseNotFoundException(command.releaseId.value.toString())

        release.requireTransitionAllowed(command.status)
        release.updateMetadata(
            versionName = command.versionName,
            minSupportedVersionCode = command.minSupportedVersionCode,
            isMandatory = command.isMandatory,
            downloadUrl = command.downloadUrl,
            sha256 = command.sha256,
            fileSizeBytes = command.fileSizeBytes,
            releaseNotes = command.releaseNotes,
            releaseDate = command.releaseDate,
            signerSubject = command.signerSubject,
            signerThumbprint = command.signerThumbprint,
        )
        release.transitionTo(command.status, command.callerId)
        return appReleaseRepository.save(release)
    }
}

data class ActivateAppReleaseCommand(val callerId: UserId, val releaseId: AppReleaseId)

/** PLATFORM_ADMIN only. */
class ActivateAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ActivateAppReleaseCommand): AppRelease {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val release = appReleaseRepository.findById(command.releaseId)
            ?: throw AppReleaseNotFoundException(command.releaseId.value.toString())
        release.activate()
        return appReleaseRepository.save(release)
    }
}

data class DeactivateAppReleaseCommand(val callerId: UserId, val releaseId: AppReleaseId)

/** PLATFORM_ADMIN only. Never deletes the row - same "disable, don't destroy" discipline the rest of this platform's admin-moderation surfaces (e.g. [ai.rojan.backend.application.platformauthority.DeactivatePlatformManagerUseCase]) already use. */
class DeactivateAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: DeactivateAppReleaseCommand): AppRelease {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val release = appReleaseRepository.findById(command.releaseId)
            ?: throw AppReleaseNotFoundException(command.releaseId.value.toString())
        release.deactivate()
        return appReleaseRepository.save(release)
    }
}

data class PublishAppReleaseCommand(val callerId: UserId, val releaseId: AppReleaseId)

/** PLATFORM_ADMIN only. DRAFT -> PUBLISHED - records publishedAt/publishedBy and locks the artifact fields for good. */
class PublishAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: PublishAppReleaseCommand): AppRelease {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val release = appReleaseRepository.findById(command.releaseId)
            ?: throw AppReleaseNotFoundException(command.releaseId.value.toString())
        release.publish(command.callerId)
        return appReleaseRepository.save(release)
    }
}

data class ArchiveAppReleaseCommand(val callerId: UserId, val releaseId: AppReleaseId)

/** PLATFORM_ADMIN only. PUBLISHED -> ARCHIVED - the public lookup stops returning it, so the next-highest published release becomes "latest" again (rollback). */
class ArchiveAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ArchiveAppReleaseCommand): AppRelease {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val release = appReleaseRepository.findById(command.releaseId)
            ?: throw AppReleaseNotFoundException(command.releaseId.value.toString())
        release.archive()
        return appReleaseRepository.save(release)
    }
}

data class RepublishAppReleaseCommand(val callerId: UserId, val releaseId: AppReleaseId)

/** PLATFORM_ADMIN only. ARCHIVED -> PUBLISHED, the one explicit way back - never a side effect of an edit. Re-stamps publishedAt/publishedBy; the artifact stays exactly the one published before. */
class RepublishAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: RepublishAppReleaseCommand): AppRelease {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val release = appReleaseRepository.findById(command.releaseId)
            ?: throw AppReleaseNotFoundException(command.releaseId.value.toString())
        release.republish(command.callerId)
        return appReleaseRepository.save(release)
    }
}

data class ListAppReleasesQuery(val callerId: UserId, val applicationId: String)

/** PLATFORM_ADMIN or PLATFORM_REVIEWER - the admin management view, includes every status (DRAFT/PUBLISHED/ARCHIVED) and inactive rows. Mirrors [ai.rojan.backend.api.platformauthority.PlatformAuthorityManagerController]'s "reviewer can read, only admin mutates" split - the task's explicit convention to reuse, not [ai.rojan.backend.api.banner.BannerController]'s stricter admin-only-for-everything shape. */
class ListAppReleasesForAdminUseCase(
    private val appReleaseRepository: AppReleaseRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListAppReleasesQuery): List<AppRelease> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        val target = resolveTarget(query.applicationId)
        return appReleaseRepository.findByTarget(target)
    }
}

data class LatestAppReleaseQuery(
    val applicationId: String,
    val callerVersionCode: Int,
    val channel: AppReleaseChannel = AppReleaseChannel.PRODUCTION,
)

/** The computed result of checking one caller's current [callerVersionCode] against the real latest [release] - never persisted, recomputed fresh on every call. */
data class LatestAppReleaseResult(
    val updateAvailable: Boolean,
    val forceUpdate: Boolean,
    val release: AppRelease,
)

/**
 * No authorization at all, by design. Only PUBLISHED+active releases on the requested channel
 * ([LatestAppReleaseQuery.channel], PRODUCTION unless a caller explicitly asks otherwise) are ever
 * considered - DRAFT, ARCHIVED and deactivated releases are invisible here.
 * - this is the public surface every Android client eventually
 * calls before login, mirroring [ai.rojan.backend.application.banner.ListActiveBannersUseCase]'s
 * own "intentionally open" shape. An app with no PUBLISHED+active release yet is a real
 * [AppReleaseNotFoundException] (404) - never a fabricated "no update available" response for data
 * that genuinely doesn't exist (the website's own `lib/downloads/release-registry.ts` follows the
 * identical "404 rather than resolve to nothing" discipline for the same reason).
 *
 * [LatestAppReleaseResult.forceUpdate] is true only when an update is actually available AND either
 * this specific release was marked mandatory, or the caller's version has fallen below the release's
 * own [AppRelease.minSupportedVersionCode] floor - a caller already on or above the latest version
 * is never told to force-update, regardless of either flag.
 */
class GetLatestAppReleaseUseCase(
    private val appReleaseRepository: AppReleaseRepository,
) {
    fun execute(query: LatestAppReleaseQuery): LatestAppReleaseResult {
        val target = resolveTarget(query.applicationId)
        val release = appReleaseRepository.findLatestPublished(target, query.channel)
            ?: throw AppReleaseNotFoundException("${query.applicationId} (${query.channel})")

        val updateAvailable = query.callerVersionCode < release.versionCode
        val forceUpdate = updateAvailable &&
            (release.isMandatory || query.callerVersionCode < release.minSupportedVersionCode)

        return LatestAppReleaseResult(updateAvailable, forceUpdate, release)
    }
}
