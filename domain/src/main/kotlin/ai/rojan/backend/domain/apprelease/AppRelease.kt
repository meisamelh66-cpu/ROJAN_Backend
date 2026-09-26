package ai.rojan.backend.domain.apprelease

import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class AppReleaseId(val value: UUID) {
    companion object {
        fun new(): AppReleaseId = AppReleaseId(UUID.randomUUID())
    }
}

/**
 * App Release Management (Super Admin Foundation): the closed set of ROJAN Android apps a release
 * can target, mirroring [ai.rojan.backend.domain.banner.BannerTarget]'s "small, DB-CHECK-enforced
 * surface" shape - adding a fourth app is explicit future work (a new migration extending the
 * CHECK constraint), never a flag flip. [applicationId] is the real Android `applicationId` every
 * client (this API's own `{applicationId}` path variable, the website's Super Admin panel, the
 * eventual Android update-check caller) actually keys on - the enum constant name is purely an
 * internal Kotlin convenience, never serialized itself.
 */
enum class AppTarget(val applicationId: String) {
    MANAGER("ai.rojan.designlab.manager"),
    CUSTOMER("ai.rojan.designlab"),
    RECEPTION("ai.rojan.designlab.reception"),
    ;

    companion object {
        fun fromApplicationId(applicationId: String): AppTarget? = entries.find { it.applicationId == applicationId }
    }
}

/** Where a release currently sits in its own lifecycle - independent of [AppRelease.isActive] (an admin can temporarily disable a PUBLISHED release, e.g. during an incident, without demoting it back to DRAFT). Only PUBLISHED+active releases are ever visible to the public "latest release" lookup. */
enum class AppReleaseStatus { DRAFT, PUBLISHED, ARCHIVED }

/**
 * A single, versioned, downloadable build of one ROJAN Android app ([target]), distributed
 * directly from the ROJAN website - not a Play Store listing (see `lib/downloads/release-registry.ts`
 * in ROJAN_Web for the existing static file-serving side this complements, not replaces: that
 * registry still serves the actual bytes; this table is the queryable metadata a Super Admin
 * manages and an Android client eventually checks against). Deliberately its own aggregate, not a
 * [ai.rojan.backend.domain.banner.Banner] or [ai.rojan.backend.domain.media.MediaAsset] row - a
 * release's lifecycle (versionCode uniqueness per app, mandatory-update math against a caller's own
 * version) has nothing in common with either.
 *
 * [versionCode] is immutable once created - the same "core identity, never touched by a metadata
 * edit" discipline [Banner.storageKey] already establishes (see [updateMetadata] and
 * [ai.rojan.backend.domain.banner.Banner.updateMetadata] for the same shape) - a release is one
 * specific build; changing its version number would silently invalidate the very identity every
 * caller (uniqueness constraint, update-check math) keys on. [minSupportedVersionCode] can never
 * exceed [versionCode] - a release cannot claim callers must be newer than the release itself.
 */
class AppRelease private constructor(
    val id: AppReleaseId,
    val target: AppTarget,
    versionName: String,
    val versionCode: Int,
    minSupportedVersionCode: Int,
    isMandatory: Boolean,
    status: AppReleaseStatus,
    downloadUrl: String,
    sha256: String,
    fileSizeBytes: Long,
    releaseNotes: String?,
    releaseDate: LocalDate,
    isActive: Boolean,
    val createdBy: UserId,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var versionName: String = versionName
        private set

    var minSupportedVersionCode: Int = minSupportedVersionCode
        private set

    var isMandatory: Boolean = isMandatory
        private set

    var status: AppReleaseStatus = status
        private set

    var downloadUrl: String = downloadUrl
        private set

    var sha256: String = sha256
        private set

    var fileSizeBytes: Long = fileSizeBytes
        private set

    var releaseNotes: String? = releaseNotes
        private set

    var releaseDate: LocalDate = releaseDate
        private set

    var isActive: Boolean = isActive
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** Every editable field except [versionCode]/[target] - see the class doc comment for why those two stay immutable. */
    fun updateMetadata(
        versionName: String,
        minSupportedVersionCode: Int,
        isMandatory: Boolean,
        status: AppReleaseStatus,
        downloadUrl: String,
        sha256: String,
        fileSizeBytes: Long,
        releaseNotes: String?,
        releaseDate: LocalDate,
    ) {
        require(versionName.isNotBlank()) { "Version name must not be blank" }
        require(minSupportedVersionCode > 0) { "Minimum supported version code must be positive" }
        require(minSupportedVersionCode <= versionCode) { "Minimum supported version code cannot exceed this release's own version code" }
        require(downloadUrl.isNotBlank()) { "Download URL must not be blank" }
        require(SHA256_PATTERN.matches(sha256)) { "SHA-256 must be exactly 64 lowercase hex characters" }
        require(fileSizeBytes > 0) { "File size must be positive" }

        this.versionName = versionName.trim()
        this.minSupportedVersionCode = minSupportedVersionCode
        this.isMandatory = isMandatory
        this.status = status
        this.downloadUrl = downloadUrl.trim()
        this.sha256 = sha256.lowercase()
        this.fileSizeBytes = fileSizeBytes
        this.releaseNotes = releaseNotes?.trim()?.takeIf { it.isNotBlank() }
        this.releaseDate = releaseDate
        touch()
    }

    fun activate() {
        isActive = true
        touch()
    }

    fun deactivate() {
        isActive = false
        touch()
    }

    private fun touch() {
        updatedAt = Instant.now()
    }

    companion object {
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        fun create(
            target: AppTarget,
            versionName: String,
            versionCode: Int,
            minSupportedVersionCode: Int,
            isMandatory: Boolean,
            status: AppReleaseStatus,
            downloadUrl: String,
            sha256: String,
            fileSizeBytes: Long,
            releaseNotes: String?,
            releaseDate: LocalDate,
            isActive: Boolean,
            createdBy: UserId,
        ): AppRelease {
            require(versionName.isNotBlank()) { "Version name must not be blank" }
            require(versionCode > 0) { "Version code must be positive" }
            require(minSupportedVersionCode > 0) { "Minimum supported version code must be positive" }
            require(minSupportedVersionCode <= versionCode) { "Minimum supported version code cannot exceed this release's own version code" }
            require(downloadUrl.isNotBlank()) { "Download URL must not be blank" }
            require(SHA256_PATTERN.matches(sha256)) { "SHA-256 must be exactly 64 lowercase hex characters" }
            require(fileSizeBytes > 0) { "File size must be positive" }

            val now = Instant.now()
            return AppRelease(
                id = AppReleaseId.new(),
                target = target,
                versionName = versionName.trim(),
                versionCode = versionCode,
                minSupportedVersionCode = minSupportedVersionCode,
                isMandatory = isMandatory,
                status = status,
                downloadUrl = downloadUrl.trim(),
                sha256 = sha256.lowercase(),
                fileSizeBytes = fileSizeBytes,
                releaseNotes = releaseNotes?.trim()?.takeIf { it.isNotBlank() },
                releaseDate = releaseDate,
                isActive = isActive,
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: AppReleaseId,
            target: AppTarget,
            versionName: String,
            versionCode: Int,
            minSupportedVersionCode: Int,
            isMandatory: Boolean,
            status: AppReleaseStatus,
            downloadUrl: String,
            sha256: String,
            fileSizeBytes: Long,
            releaseNotes: String?,
            releaseDate: LocalDate,
            isActive: Boolean,
            createdBy: UserId,
            createdAt: Instant,
            updatedAt: Instant,
        ): AppRelease = AppRelease(
            id, target, versionName, versionCode, minSupportedVersionCode, isMandatory, status, downloadUrl,
            sha256, fileSizeBytes, releaseNotes, releaseDate, isActive, createdBy, createdAt, updatedAt,
        )
    }
}
