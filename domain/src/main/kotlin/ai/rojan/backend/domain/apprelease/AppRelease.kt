package ai.rojan.backend.domain.apprelease

import ai.rojan.backend.domain.common.InvalidAppReleaseStatusTransitionException
import ai.rojan.backend.domain.common.PublishedAppReleaseArtifactImmutableException
import ai.rojan.backend.domain.user.UserId
import java.net.URI
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

/** Where a release currently sits in its own lifecycle - independent of [AppRelease.isActive] (an admin can temporarily disable a PUBLISHED release, e.g. during an incident, without demoting it back to DRAFT). Only PUBLISHED+active releases are ever visible to the public "latest release" lookup. Transitions are explicit - see [AppRelease.transitionTo]. */
enum class AppReleaseStatus { DRAFT, PUBLISHED, ARCHIVED }

/**
 * App Release Hardening: which distribution stream a release belongs to. Part of a release's
 * identity together with [AppRelease.target] and [AppRelease.versionCode] (the uniqueness key is
 * app + channel + versionCode), immutable once created, and DB-CHECK-enforced the same closed-set
 * way [AppTarget] is. Every existing caller that never names a channel gets [PRODUCTION].
 */
enum class AppReleaseChannel { PRODUCTION, BETA }

/**
 * A single, versioned, downloadable build of one ROJAN app ([target]), distributed directly (never
 * a store listing) - the queryable metadata a Super Admin manages and each client's update check
 * reads. The installer/APK bytes themselves live outside this backend (a static nginx download
 * location or the website's own static files); this aggregate only records where they are and how
 * to verify them. Deliberately its own aggregate, not a [ai.rojan.backend.domain.banner.Banner] or
 * [ai.rojan.backend.domain.media.MediaAsset] row.
 *
 * Identity: [target], [channel] and [versionCode] are immutable once created - a release is one
 * specific build; changing any of them would silently invalidate the very identity every caller
 * (uniqueness constraint, update-check math) keys on. [minSupportedVersionCode] can never exceed
 * [versionCode].
 *
 * Published artifact immutability: once a release has ever been published ([publishedAt] set), its
 * artifact - [versionName], [downloadUrl], [sha256], [fileSizeBytes] - can never change again, not
 * even after archiving. Clients may already have verified and installed exactly those bytes; a
 * different build must be a new release with a new versionCode. [minSupportedVersionCode],
 * [isMandatory], [releaseNotes], [releaseDate] and the signer metadata stay editable.
 *
 * Concurrency: [version] is the stored row's optimistic-lock version; a save based on an outdated
 * version fails with [ai.rojan.backend.domain.common.AppReleaseConcurrentModificationException]
 * instead of overwriting newer data (e.g. a publish that happened in between).
 *
 * Signer metadata ([signerSubject]/[signerThumbprint]) is informational only - a record of which
 * certificate signed the artifact, for admins and diagnostics. Clients must never treat it as their
 * trust root: it comes from the same server as [downloadUrl], so anyone able to alter one could
 * alter the other. Trusted signer identities belong in the client itself.
 */
class AppRelease private constructor(
    val id: AppReleaseId,
    val target: AppTarget,
    val channel: AppReleaseChannel,
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
    signerSubject: String?,
    signerThumbprint: String?,
    publishedAt: Instant?,
    publishedBy: UserId?,
    val createdBy: UserId,
    val createdAt: Instant,
    updatedAt: Instant,
    /**
     * Optimistic-concurrency version of the stored row this object was loaded from (null for a
     * release that has never been saved). Persistence refuses to save an object whose version is
     * no longer the stored one, so a request that loaded a release before another request changed
     * it (e.g. published it) can never silently overwrite that newer state.
     */
    val version: Long? = null,
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

    var signerSubject: String? = signerSubject
        private set

    var signerThumbprint: String? = signerThumbprint
        private set

    /** When this release was last (re-)published; null until its first publish. */
    var publishedAt: Instant? = publishedAt
        private set

    /** Who last (re-)published this release; null until its first publish. */
    var publishedBy: UserId? = publishedBy
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** True once this release has ever been published - its artifact fields are locked from then on (see the class doc comment). */
    val isArtifactLocked: Boolean
        get() = publishedAt != null

    /**
     * Every editable field except the identity ([target]/[channel]/[versionCode]) and [status] -
     * status changes go through [transitionTo]/[publish]/[archive]/[republish] only. A null
     * [signerSubject]/[signerThumbprint] means "leave unchanged", so callers that don't know about
     * signer metadata (the existing Super Admin form) never wipe it. Validates everything before
     * mutating anything, so a rejected edit leaves this release untouched.
     */
    fun updateMetadata(
        versionName: String,
        minSupportedVersionCode: Int,
        isMandatory: Boolean,
        downloadUrl: String,
        sha256: String,
        fileSizeBytes: Long,
        releaseNotes: String?,
        releaseDate: LocalDate,
        signerSubject: String? = null,
        signerThumbprint: String? = null,
    ) {
        val normalizedVersionName = versionName.trim()
        val normalizedDownloadUrl = downloadUrl.trim()
        val normalizedSha256 = sha256.lowercase()
        validateVersionName(normalizedVersionName)
        validateVersion(target, normalizedVersionName, versionCode, minSupportedVersionCode)
        validateDownloadUrl(normalizedDownloadUrl)
        require(SHA256_PATTERN.matches(normalizedSha256)) { "SHA-256 must be exactly 64 lowercase hex characters" }
        require(fileSizeBytes > 0) { "File size must be positive" }
        val newSignerSubject = signerSubject?.let { normalizeSignerSubject(it) } ?: this.signerSubject
        val newSignerThumbprint = signerThumbprint?.let { normalizeSignerThumbprint(it) } ?: this.signerThumbprint

        if (isArtifactLocked) {
            if (normalizedVersionName != this.versionName) throw PublishedAppReleaseArtifactImmutableException("versionName")
            if (normalizedDownloadUrl != this.downloadUrl) throw PublishedAppReleaseArtifactImmutableException("downloadUrl")
            if (normalizedSha256 != this.sha256) throw PublishedAppReleaseArtifactImmutableException("sha256")
            if (fileSizeBytes != this.fileSizeBytes) throw PublishedAppReleaseArtifactImmutableException("fileSizeBytes")
        }

        this.versionName = normalizedVersionName
        this.minSupportedVersionCode = minSupportedVersionCode
        this.isMandatory = isMandatory
        this.downloadUrl = normalizedDownloadUrl
        this.sha256 = normalizedSha256
        this.fileSizeBytes = fileSizeBytes
        this.releaseNotes = releaseNotes?.trim()?.takeIf { it.isNotBlank() }
        this.releaseDate = releaseDate
        this.signerSubject = newSignerSubject
        this.signerThumbprint = newSignerThumbprint
        touch()
    }

    /** Throws [InvalidAppReleaseStatusTransitionException] unless [transitionTo] would accept [target] - lets a combined edit reject a bad status before touching anything else. */
    fun requireTransitionAllowed(target: AppReleaseStatus) {
        if (target == status) return
        val allowed = (status == AppReleaseStatus.DRAFT && target == AppReleaseStatus.PUBLISHED) ||
            (status == AppReleaseStatus.PUBLISHED && target == AppReleaseStatus.ARCHIVED)
        if (!allowed) throw InvalidAppReleaseStatusTransitionException(status, target)
    }

    /**
     * The status change a generic edit may request: staying put, DRAFT -> PUBLISHED, or
     * PUBLISHED -> ARCHIVED. ARCHIVED -> PUBLISHED is deliberately NOT accepted here - putting an
     * archived build back in front of clients must be the explicit [republish] operation, never a
     * side effect of saving a form. Everything else (e.g. back to DRAFT) is rejected.
     */
    fun transitionTo(target: AppReleaseStatus, by: UserId) {
        requireTransitionAllowed(target)
        when {
            target == status -> Unit
            target == AppReleaseStatus.PUBLISHED -> publish(by)
            target == AppReleaseStatus.ARCHIVED -> archive()
        }
    }

    /** DRAFT -> PUBLISHED. Records [publishedAt]/[publishedBy] and locks the artifact fields. */
    fun publish(by: UserId) {
        if (status != AppReleaseStatus.DRAFT) throw InvalidAppReleaseStatusTransitionException(status, AppReleaseStatus.PUBLISHED)
        markPublished(by)
    }

    /** PUBLISHED -> ARCHIVED. The public lookup stops returning it; [publishedAt]/[publishedBy] keep the last publish on record. */
    fun archive() {
        if (status != AppReleaseStatus.PUBLISHED) throw InvalidAppReleaseStatusTransitionException(status, AppReleaseStatus.ARCHIVED)
        status = AppReleaseStatus.ARCHIVED
        touch()
    }

    /** ARCHIVED -> PUBLISHED, the explicit re-publish operation. Re-stamps [publishedAt]/[publishedBy] with this publish. The artifact is still locked - it is the same build that was published before. */
    fun republish(by: UserId) {
        if (status != AppReleaseStatus.ARCHIVED) throw InvalidAppReleaseStatusTransitionException(status, AppReleaseStatus.PUBLISHED)
        markPublished(by)
    }

    fun activate() {
        isActive = true
        touch()
    }

    fun deactivate() {
        isActive = false
        touch()
    }

    private fun markPublished(by: UserId) {
        val now = Instant.now()
        status = AppReleaseStatus.PUBLISHED
        publishedAt = now
        publishedBy = by
        updatedAt = now
    }

    private fun touch() {
        updatedAt = Instant.now()
    }

    companion object {
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        /** Each component 0-999 without leading zeros, so [receptionVersionCode] is a 1:1 mapping. */
        private val SEMVER_CORE_PATTERN = Regex("""^(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})$""")

        /** Authenticode certificate thumbprint: SHA-1 (40 hex, what Windows shows) or SHA-256 (64 hex), stored uppercase. */
        private val THUMBPRINT_PATTERN = Regex("^([0-9A-F]{40}|[0-9A-F]{64})$")

        const val MAX_SIGNER_SUBJECT_LENGTH = 512

        /**
         * The one versionCode a ROJAN Reception (Windows Desktop) `MAJOR.MINOR.PATCH` versionName
         * maps to: MAJOR * 1,000,000 + MINOR * 1,000 + PATCH. The Desktop client derives its own
         * versionCode from its assembly version with the same formula, so integer comparison on
         * the server and semantic-version comparison on the client always agree.
         */
        fun receptionVersionCode(major: Int, minor: Int, patch: Int): Int = major * 1_000_000 + minor * 1_000 + patch

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
            channel: AppReleaseChannel = AppReleaseChannel.PRODUCTION,
            signerSubject: String? = null,
            signerThumbprint: String? = null,
        ): AppRelease {
            val normalizedVersionName = versionName.trim()
            val normalizedDownloadUrl = downloadUrl.trim()
            val normalizedSha256 = sha256.lowercase()
            validateVersionName(normalizedVersionName)
            require(versionCode > 0) { "Version code must be positive" }
            validateVersion(target, normalizedVersionName, versionCode, minSupportedVersionCode)
            validateDownloadUrl(normalizedDownloadUrl)
            require(SHA256_PATTERN.matches(normalizedSha256)) { "SHA-256 must be exactly 64 lowercase hex characters" }
            require(fileSizeBytes > 0) { "File size must be positive" }
            // A new release starts as a DRAFT, or is published in the same step (the existing Super
            // Admin form allows that). It can never be born ARCHIVED - that state only exists after
            // a real publish.
            require(status != AppReleaseStatus.ARCHIVED) { "A new release can only be created as DRAFT or PUBLISHED" }

            val now = Instant.now()
            val published = status == AppReleaseStatus.PUBLISHED
            return AppRelease(
                id = AppReleaseId.new(),
                target = target,
                channel = channel,
                versionName = normalizedVersionName,
                versionCode = versionCode,
                minSupportedVersionCode = minSupportedVersionCode,
                isMandatory = isMandatory,
                status = status,
                downloadUrl = normalizedDownloadUrl,
                sha256 = normalizedSha256,
                fileSizeBytes = fileSizeBytes,
                releaseNotes = releaseNotes?.trim()?.takeIf { it.isNotBlank() },
                releaseDate = releaseDate,
                isActive = isActive,
                signerSubject = signerSubject?.let(::normalizeSignerSubject),
                signerThumbprint = signerThumbprint?.let(::normalizeSignerThumbprint),
                publishedAt = if (published) now else null,
                publishedBy = if (published) createdBy else null,
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: AppReleaseId,
            target: AppTarget,
            channel: AppReleaseChannel,
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
            signerSubject: String?,
            signerThumbprint: String?,
            publishedAt: Instant?,
            publishedBy: UserId?,
            createdBy: UserId,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long?,
        ): AppRelease = AppRelease(
            id, target, channel, versionName, versionCode, minSupportedVersionCode, isMandatory, status, downloadUrl,
            sha256, fileSizeBytes, releaseNotes, releaseDate, isActive, signerSubject, signerThumbprint,
            publishedAt, publishedBy, createdBy, createdAt, updatedAt, version,
        )

        private fun validateVersionName(versionName: String) {
            require(versionName.isNotBlank()) { "Version name must not be blank" }
        }

        private fun validateVersion(target: AppTarget, versionName: String, versionCode: Int, minSupportedVersionCode: Int) {
            require(minSupportedVersionCode > 0) { "Minimum supported version code must be positive" }
            require(minSupportedVersionCode <= versionCode) { "Minimum supported version code cannot exceed this release's own version code" }
            if (target == AppTarget.RECEPTION) {
                val match = SEMVER_CORE_PATTERN.matchEntire(versionName)
                    ?: throw IllegalArgumentException("ROJAN Reception versionName must be MAJOR.MINOR.PATCH (each 0-999, no leading zeros), e.g. 1.0.1")
                val (major, minor, patch) = match.destructured
                val expected = receptionVersionCode(major.toInt(), minor.toInt(), patch.toInt())
                require(versionCode == expected) {
                    "ROJAN Reception versionCode must be MAJOR*1000000 + MINOR*1000 + PATCH - $versionName requires $expected, got $versionCode"
                }
            }
        }

        /** Only absolute https:// URLs with a host - clients download and run this file. */
        private fun validateDownloadUrl(downloadUrl: String) {
            require(downloadUrl.isNotBlank()) { "Download URL must not be blank" }
            val uri = runCatching { URI(downloadUrl) }.getOrNull()
            require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "Download URL must be an absolute https:// URL"
            }
        }

        private fun normalizeSignerSubject(value: String): String? {
            val trimmed = value.trim()
            require(trimmed.length <= MAX_SIGNER_SUBJECT_LENGTH) { "Signer subject must be at most $MAX_SIGNER_SUBJECT_LENGTH characters" }
            return trimmed.takeIf { it.isNotEmpty() }
        }

        private fun normalizeSignerThumbprint(value: String): String? {
            val normalized = value.replace(" ", "").replace(":", "").trim().uppercase()
            if (normalized.isEmpty()) return null
            require(THUMBPRINT_PATTERN.matches(normalized)) { "Signer thumbprint must be 40 (SHA-1) or 64 (SHA-256) hex characters" }
            return normalized
        }
    }
}
