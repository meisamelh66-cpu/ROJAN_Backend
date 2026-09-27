package ai.rojan.backend.api.apprelease

import ai.rojan.backend.application.apprelease.GetLatestAppReleaseUseCase
import ai.rojan.backend.application.apprelease.LatestAppReleaseQuery
import ai.rojan.backend.application.apprelease.LatestAppReleaseResult
import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The real, unauthenticated "check for update" surface every ROJAN client (Manager/Customer
 * Android, Reception Windows Desktop) calls, before login - already covered by `SecurityConfig`'s
 * existing public-prefix permit-all rule for everything under `/api/v1/public`, no security config
 * change needed. Deliberately separate from
 * [AppReleaseController] (PLATFORM_ADMIN/PLATFORM_REVIEWER management), mirroring the platform's
 * existing public/admin split (e.g. `PublicBannerController`/`BannerController`). An app with no
 * PUBLISHED+active release yet on the requested channel 404s (`AppReleaseNotFoundException`) -
 * never a fabricated "no update available" response.
 *
 * `channel` defaults to PRODUCTION, so every existing caller keeps getting production releases
 * without knowing channels exist. The response is always `Cache-Control: no-store` - a cached
 * answer could hide a newly published (or pulled) release from clients.
 */
@RestController
@RequestMapping("/api/v1/public/app-releases")
@Tag(name = "Public - App Releases")
class PublicAppReleaseController(
    private val getLatestAppReleaseUseCase: GetLatestAppReleaseUseCase,
) {

    @GetMapping("/{applicationId}/latest")
    @Operation(summary = "Check whether a newer release exists for the caller's current versionCode on a channel (default PRODUCTION), and whether updating is mandatory")
    fun latest(
        @PathVariable applicationId: String,
        @RequestParam versionCode: Int,
        @RequestParam(defaultValue = "PRODUCTION") channel: AppReleaseChannel,
    ): ResponseEntity<PublicLatestReleaseResponse> {
        val result = getLatestAppReleaseUseCase.execute(LatestAppReleaseQuery(applicationId, versionCode, channel))
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(result.toResponse())
    }

    private fun LatestAppReleaseResult.toResponse() = PublicLatestReleaseResponse(
        updateAvailable = updateAvailable,
        forceUpdate = forceUpdate,
        latestVersion = release.versionName,
        latestVersionCode = release.versionCode,
        minSupportedVersionCode = release.minSupportedVersionCode,
        isMandatory = release.isMandatory,
        channel = release.channel,
        downloadUrl = release.downloadUrl,
        sha256 = release.sha256,
        fileSizeBytes = release.fileSizeBytes,
        releaseNotes = release.releaseNotes,
        releaseDate = release.releaseDate,
        publishedAt = release.publishedAt,
    )
}
