package ai.rojan.backend.api.apprelease

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.apprelease.ActivateAppReleaseCommand
import ai.rojan.backend.application.apprelease.ActivateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.CreateAppReleaseCommand
import ai.rojan.backend.application.apprelease.CreateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.DeactivateAppReleaseCommand
import ai.rojan.backend.application.apprelease.DeactivateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.ListAppReleasesForAdminUseCase
import ai.rojan.backend.application.apprelease.ListAppReleasesQuery
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
            ),
        ).toResponse()
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
        createdBy = createdBy.value,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
