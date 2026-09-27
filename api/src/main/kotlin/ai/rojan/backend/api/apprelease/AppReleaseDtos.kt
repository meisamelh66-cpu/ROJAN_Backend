package ai.rojan.backend.api.apprelease

import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Admin view - every field, including audit metadata. Never returned from the public endpoint - see [PublicLatestReleaseResponse]. */
data class AppReleaseResponse(
    val id: UUID,
    val applicationId: String,
    val channel: AppReleaseChannel,
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
    val signerSubject: String?,
    val signerThumbprint: String?,
    val publishedAt: Instant?,
    val publishedBy: UUID?,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * `applicationId` is validated against the known [ai.rojan.backend.domain.apprelease.AppTarget] set
 * inside the use case (`resolveTarget`), not via Bean Validation here - it's a real Android app id
 * string, not a small closed enum Spring can bind/reject on its own the way
 * [ai.rojan.backend.domain.banner.BannerTarget] does for banners. `sha256`/`versionCode`/
 * `minSupportedVersionCode`/`fileSizeBytes` still get real Bean Validation since those shapes are
 * always structurally checkable independent of which app they belong to.
 */
data class CreateAppReleaseRequest(
    @field:NotBlank
    val applicationId: String,

    @field:NotBlank
    @field:Size(max = 32)
    val versionName: String,

    @field:Min(1)
    val versionCode: Int,

    @field:Min(1)
    val minSupportedVersionCode: Int,

    val isMandatory: Boolean = false,

    val status: AppReleaseStatus = AppReleaseStatus.DRAFT,

    @field:NotBlank
    @field:Size(max = 2000)
    val downloadUrl: String,

    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "must be exactly 64 hex characters")
    val sha256: String,

    @field:Min(1)
    val fileSizeBytes: Long,

    @field:Size(max = 4000)
    val releaseNotes: String? = null,

    val releaseDate: LocalDate,

    val isActive: Boolean = true,

    /** Defaults to PRODUCTION. Immutable once created - part of the release's identity with applicationId/versionCode. */
    val channel: AppReleaseChannel = AppReleaseChannel.PRODUCTION,

    /** Optional, informational only - which certificate signed the artifact. Never a client trust root. */
    @field:Size(max = 512)
    val signerSubject: String? = null,

    /** Optional, informational only - never a client trust root. SHA-1 (40 hex) or SHA-256 (64 hex); spaces/colons tolerated. */
    @field:Size(max = 200)
    val signerThumbprint: String? = null,
)

/** Never includes `applicationId`/`channel`/`versionCode` - all three are immutable once a release is created, see [ai.rojan.backend.domain.apprelease.AppRelease]'s own doc comment. */
data class UpdateAppReleaseRequest(
    @field:NotBlank
    @field:Size(max = 32)
    val versionName: String,

    @field:Min(1)
    val minSupportedVersionCode: Int,

    val isMandatory: Boolean,

    val status: AppReleaseStatus,

    @field:NotBlank
    @field:Size(max = 2000)
    val downloadUrl: String,

    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "must be exactly 64 hex characters")
    val sha256: String,

    @field:Min(1)
    val fileSizeBytes: Long,

    @field:Size(max = 4000)
    val releaseNotes: String? = null,

    val releaseDate: LocalDate,

    /** Null leaves the stored value unchanged (the existing Super Admin form never sends it). */
    @field:Size(max = 512)
    val signerSubject: String? = null,

    /** Null leaves the stored value unchanged. SHA-1 (40 hex) or SHA-256 (64 hex); spaces/colons tolerated. */
    @field:Size(max = 200)
    val signerThumbprint: String? = null,
)

/**
 * The public "check for update" response - deliberately a distinct, narrower shape from
 * [AppReleaseResponse]. Never includes `createdBy`/`publishedBy`, the release's internal database
 * id, `status`, `isActive` or signer metadata - none of that is the calling app's business, matching
 * the task's own explicit "do not expose internal/admin-only metadata" requirement.
 * [minSupportedVersionCode]/[isMandatory] are the raw inputs to [forceUpdate], exposed so a client
 * can explain why an update is required.
 */
data class PublicLatestReleaseResponse(
    val updateAvailable: Boolean,
    val forceUpdate: Boolean,
    val latestVersion: String,
    val latestVersionCode: Int,
    val minSupportedVersionCode: Int,
    val isMandatory: Boolean,
    val channel: AppReleaseChannel,
    val downloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val releaseNotes: String?,
    val releaseDate: LocalDate,
    val publishedAt: Instant?,
)
