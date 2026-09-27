package ai.rojan.backend.api.apprelease

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.apprelease.ActivateAppReleaseCommand
import ai.rojan.backend.application.apprelease.ActivateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.ArchiveAppReleaseCommand
import ai.rojan.backend.application.apprelease.ArchiveAppReleaseUseCase
import ai.rojan.backend.application.apprelease.CreateAppReleaseCommand
import ai.rojan.backend.application.apprelease.CreateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.DeactivateAppReleaseCommand
import ai.rojan.backend.application.apprelease.DeactivateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.ListAppReleasesForAdminUseCase
import ai.rojan.backend.application.apprelease.ListAppReleasesQuery
import ai.rojan.backend.application.apprelease.PublishAppReleaseCommand
import ai.rojan.backend.application.apprelease.PublishAppReleaseUseCase
import ai.rojan.backend.application.apprelease.RepublishAppReleaseCommand
import ai.rojan.backend.application.apprelease.RepublishAppReleaseUseCase
import ai.rojan.backend.application.apprelease.UpdateAppReleaseCommand
import ai.rojan.backend.application.apprelease.UpdateAppReleaseUseCase
import ai.rojan.backend.domain.apprelease.AppRelease
import ai.rojan.backend.domain.apprelease.AppReleaseId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * App Release Management (Super Admin Foundation) - PLATFORM_ADMIN or PLATFORM_REVIEWER may list,
 * PLATFORM_ADMIN only may create/update/activate/deactivate. Mirrors
 * [ai.rojan.backend.api.platformauthority.PlatformAuthorityManagerController]'s own admin-vs-reviewer
 * split (the task's explicit convention to reuse) rather than [ai.rojan.backend.api.banner.BannerController]'s
 * stricter admin-only-for-everything shape - this is an operational surface both roles legitimately
 * read, but only PLATFORM_ADMIN changes what an app actually distributes. Authorization is entirely
 * [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver] inside each use
 * case, never checked here directly.
 *
 * `applicationId` binds as a plain `String` (query param here, request-body field on create) -
 * unlike [ai.rojan.backend.api.banner.BannerController]'s `target: BannerTarget` enum binding, a
 * real Android application id can't fail-closed at Spring's own conversion layer; each use case
 * validates it against [ai.rojan.backend.domain.apprelease.AppTarget] itself
 * (`InvalidApplicationIdException`, mapped to 400).
 *
 * Status changes are explicit: `PUT` may only keep the status, publish a DRAFT or archive a
 * PUBLISHED release (the existing Super Admin form sends `status` on every save), while the
 * dedicated `/publish`, `/archive` and `/republish` operations each do exactly one transition.
 * Re-publishing an ARCHIVED release is only possible through `/republish`. Once published, a
 * release's artifact (versionName/downloadUrl/sha256/fileSizeBytes) is locked - see
 * [ai.rojan.backend.domain.apprelease.AppRelease]'s own doc comment.
 *
 * The public, unauthenticated read surface every Android client eventually calls before login lives
 * separately at [PublicAppReleaseController] - `/api/v1/public/app-releases/{applicationId}/latest`,
 * mirroring the platform's existing public/admin split (e.g. `PublicBannerController`/`BannerController`).
 */
@RestController
@RequestMapping("/api/v1/platform-authority/app-releases")
@Tag(name = "Platform Authority - App Releases")
class AppReleaseController(
    private val createAppReleaseUseCase: CreateAppReleaseUseCase,
    private val updateAppReleaseUseCase: UpdateAppReleaseUseCase,
    private val activateAppReleaseUseCase: ActivateAppReleaseUseCase,
    private val deactivateAppReleaseUseCase: DeactivateAppReleaseUseCase,
    private val listAppReleasesForAdminUseCase: ListAppReleasesForAdminUseCase,
    private val publishAppReleaseUseCase: PublishAppReleaseUseCase,
    private val archiveAppReleaseUseCase: ArchiveAppReleaseUseCase,
    private val republishAppReleaseUseCase: RepublishAppReleaseUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "List every release for one app, every status, active and inactive (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun list(
        @RequestParam applicationId: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<AppReleaseResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listAppReleasesForAdminUseCase.execute(ListAppReleasesQuery(callerId, applicationId)).map { it.toResponse() }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new release for an app (PLATFORM_ADMIN only)")
    fun create(
        @Valid @RequestBody request: CreateAppReleaseRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): AppReleaseResponse {
        val callerId = currentUserResolver.resolve(principal)
        return createAppReleaseUseCase.execute(
            CreateAppReleaseCommand(
                callerId = callerId,
                applicationId = request.applicationId,
                versionName = request.versionName,
                versionCode = request.versionCode,
                minSupportedVersionCode = request.minSupportedVersionCode,
                isMandatory = request.isMandatory,
                status = request.status,
                downloadUrl = request.downloadUrl,
                sha256 = request.sha256,
                fileSizeBytes = request.fileSizeBytes,
                releaseNotes = request.releaseNotes,
                releaseDate = request.releaseDate,
                isActive = request.isActive,
                channel = request.channel,
                signerSubject = request.signerSubject,
                signerThumbprint = request.signerThumbprint,
            ),
        ).toResponse()
    }

    @PutMapping("/{releaseId}")
    @Operation(summary = "Edit a release's metadata - never its applicationId/versionCode (PLATFORM_ADMIN only)")
    fun update(
        @PathVariable releaseId: UUID,
        @Valid @RequestBody request: UpdateAppReleaseRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): AppReleaseResponse {
        val callerId = currentUserResolver.resolve(principal)
        return updateAppReleaseUseCase.execute(
            UpdateAppReleaseCommand(
                callerId = callerId,
                releaseId = AppReleaseId(releaseId),
                versionName = request.versionName,
                minSupportedVersionCode = request.minSupportedVersionCode,
                isMandatory = request.isMandatory,
                status = request.status,
                downloadUrl = request.downloadUrl,
                sha256 = request.sha256,
                fileSizeBytes = request.fileSizeBytes,
                releaseNotes = request.releaseNotes,
                releaseDate = request.releaseDate,
                signerSubject = request.signerSubject,
                signerThumbprint = request.signerThumbprint,
            ),
        ).toResponse()
    }

    @PostMapping("/{releaseId}/publish")
    @Operation(summary = "Publish a DRAFT release - records publishedAt/publishedBy and locks its artifact (PLATFORM_ADMIN only)")
    fun publish(@PathVariable releaseId: UUID, @AuthenticationPrincipal principal: UserDetails): AppReleaseResponse {
        val callerId = currentUserResolver.resolve(principal)
        return publishAppReleaseUseCase.execute(PublishAppReleaseCommand(callerId, AppReleaseId(releaseId))).toResponse()
    }

    @PostMapping("/{releaseId}/archive")
    @Operation(summary = "Archive a PUBLISHED release - it stops being offered as the latest release (PLATFORM_ADMIN only)")
    fun archive(@PathVariable releaseId: UUID, @AuthenticationPrincipal principal: UserDetails): AppReleaseResponse {
        val callerId = currentUserResolver.resolve(principal)
        return archiveAppReleaseUseCase.execute(ArchiveAppReleaseCommand(callerId, AppReleaseId(releaseId))).toResponse()
    }

    @PostMapping("/{releaseId}/republish")
    @Operation(summary = "Explicitly re-publish an ARCHIVED release - same artifact, new publishedAt/publishedBy (PLATFORM_ADMIN only)")
    fun republish(@PathVariable releaseId: UUID, @AuthenticationPrincipal principal: UserDetails): AppReleaseResponse {
        val callerId = currentUserResolver.resolve(principal)
        return republishAppReleaseUseCase.execute(RepublishAppReleaseCommand(callerId, AppReleaseId(releaseId))).toResponse()
    }

    @PostMapping("/{releaseId}/activate")
    @Operation(summary = "Re-enable a release (PLATFORM_ADMIN only)")
    fun activate(@PathVariable releaseId: UUID, @AuthenticationPrincipal principal: UserDetails): AppReleaseResponse {
        val callerId = currentUserResolver.resolve(principal)
        return activateAppReleaseUseCase.execute(ActivateAppReleaseCommand(callerId, AppReleaseId(releaseId))).toResponse()
    }

    @PostMapping("/{releaseId}/deactivate")
    @Operation(summary = "Disable a release without deleting it (PLATFORM_ADMIN only)")
    fun deactivate(@PathVariable releaseId: UUID, @AuthenticationPrincipal principal: UserDetails): AppReleaseResponse {
        val callerId = currentUserResolver.resolve(principal)
        return deactivateAppReleaseUseCase.execute(DeactivateAppReleaseCommand(callerId, AppReleaseId(releaseId))).toResponse()
    }

    private fun AppRelease.toResponse() = AppReleaseResponse(
        id = id.value,
        applicationId = target.applicationId,
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
        publishedBy = publishedBy?.value,
        createdBy = createdBy.value,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
