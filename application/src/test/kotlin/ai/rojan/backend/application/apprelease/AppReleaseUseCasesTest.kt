package ai.rojan.backend.application.apprelease

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.apprelease.AppRelease
import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import ai.rojan.backend.domain.apprelease.AppReleaseId
import ai.rojan.backend.domain.apprelease.AppReleaseRepository
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import ai.rojan.backend.domain.apprelease.AppTarget
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.AppReleaseNotFoundException
import ai.rojan.backend.domain.common.AppReleaseVersionCodeAlreadyExistsException
import ai.rojan.backend.domain.common.InvalidAppReleaseStatusTransitionException
import ai.rojan.backend.domain.common.InvalidApplicationIdException
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.PublishedAppReleaseArtifactImmutableException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/** Mirrors [ai.rojan.backend.application.banner.BannerUseCasesTest]'s in-memory style, same as every other use-case test in this module. */
private class InMemoryAppReleaseRepository : AppReleaseRepository {
    private val store = mutableMapOf<AppReleaseId, AppRelease>()
    override fun findById(id: AppReleaseId): AppRelease? = store[id]
    override fun findByTarget(target: AppTarget): List<AppRelease> =
        store.values.filter { it.target == target }.sortedByDescending { it.versionCode }
    override fun findLatestPublished(target: AppTarget, channel: AppReleaseChannel): AppRelease? =
        store.values.filter { it.target == target && it.channel == channel && it.status == AppReleaseStatus.PUBLISHED && it.isActive }
            .maxByOrNull { it.versionCode }
    override fun existsByTargetAndChannelAndVersionCode(target: AppTarget, channel: AppReleaseChannel, versionCode: Int): Boolean =
        store.values.any { it.target == target && it.channel == channel && it.versionCode == versionCode }
    override fun save(release: AppRelease): AppRelease = release.also { store[it.id] = it }
}

class AppReleaseUseCasesTest {

    private val userRepository = InMemorySalonUserRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val appReleaseRepository = InMemoryAppReleaseRepository()

    private val createUseCase = CreateAppReleaseUseCase(appReleaseRepository, platformAuthorization)
    private val updateUseCase = UpdateAppReleaseUseCase(appReleaseRepository, platformAuthorization)
    private val activateUseCase = ActivateAppReleaseUseCase(appReleaseRepository, platformAuthorization)
    private val deactivateUseCase = DeactivateAppReleaseUseCase(appReleaseRepository, platformAuthorization)
    private val listUseCase = ListAppReleasesForAdminUseCase(appReleaseRepository, platformAuthorization)
    private val latestUseCase = GetLatestAppReleaseUseCase(appReleaseRepository)
    private val publishUseCase = PublishAppReleaseUseCase(appReleaseRepository, platformAuthorization)
    private val archiveUseCase = ArchiveAppReleaseUseCase(appReleaseRepository, platformAuthorization)
    private val republishUseCase = RepublishAppReleaseUseCase(appReleaseRepository, platformAuthorization)

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555050${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val reviewer = User.registerWithPhone(nextPhone(), "Reviewer", UserRole.PLATFORM_REVIEWER).also { userRepository.save(it) }
    private val customer = User.registerWithPhone(nextPhone(), "Customer", UserRole.CUSTOMER).also { userRepository.save(it) }

    private fun createCommand(
        applicationId: String = AppTarget.MANAGER.applicationId,
        versionCode: Int = 2,
        minSupportedVersionCode: Int = 1,
        isMandatory: Boolean = false,
        status: AppReleaseStatus = AppReleaseStatus.PUBLISHED,
        isActive: Boolean = true,
        channel: AppReleaseChannel = AppReleaseChannel.PRODUCTION,
    ) = CreateAppReleaseCommand(
        callerId = admin.id,
        applicationId = applicationId,
        versionName = "1.0.$versionCode",
        versionCode = versionCode,
        minSupportedVersionCode = minSupportedVersionCode,
        isMandatory = isMandatory,
        status = status,
        downloadUrl = "https://rojanai.ir/downloads/manager/ROJAN-AI-Manager-1.0.$versionCode.apk",
        sha256 = "2898b950da790e12f7197fd0cf30c6543ebff9e929d45c533056f13f2ed7efc" + "d",
        fileSizeBytes = 6_064_180L,
        releaseNotes = "Bug fixes",
        releaseDate = LocalDate.of(2026, 9, 26),
        isActive = isActive,
        channel = channel,
    )

    /** An update command that re-sends [release] unchanged (the way the Super Admin form saves), with [status] and any overrides applied via copy(). */
    private fun unchangedUpdate(release: AppRelease, status: AppReleaseStatus = release.status) = UpdateAppReleaseCommand(
        callerId = admin.id,
        releaseId = release.id,
        versionName = release.versionName,
        minSupportedVersionCode = release.minSupportedVersionCode,
        isMandatory = release.isMandatory,
        status = status,
        downloadUrl = release.downloadUrl,
        sha256 = release.sha256,
        fileSizeBytes = release.fileSizeBytes,
        releaseNotes = release.releaseNotes,
        releaseDate = release.releaseDate,
    )

    private fun receptionCommand(versionName: String, versionCode: Int, status: AppReleaseStatus = AppReleaseStatus.DRAFT) =
        createCommand(applicationId = AppTarget.RECEPTION.applicationId, versionCode = versionCode, status = status)
            .copy(versionName = versionName, downloadUrl = "https://rojanai.ir/download/reception/rojan-reception-$versionName-win-x64-setup.exe")

    // ---------- create: admin-only ----------

    @Test
    fun `an admin can create a real release for Manager`() {
        val release = createUseCase.execute(createCommand())
        assertEquals(AppTarget.MANAGER, release.target)
        assertEquals(2, release.versionCode)
    }

    @Test
    fun `a customer cannot create a release`() {
        assertThrows<PlatformAccessDeniedException> {
            createUseCase.execute(createCommand().copy(callerId = customer.id))
        }
    }

    @Test
    fun `a reviewer cannot create a release`() {
        assertThrows<PlatformAccessDeniedException> {
            createUseCase.execute(createCommand().copy(callerId = reviewer.id))
        }
    }

    @Test
    fun `an unknown applicationId is rejected`() {
        assertThrows<InvalidApplicationIdException> {
            createUseCase.execute(createCommand(applicationId = "com.example.unknown"))
        }
    }

    @Test
    fun `a duplicate versionCode for the same app is rejected`() {
        createUseCase.execute(createCommand(versionCode = 5))
        assertThrows<AppReleaseVersionCodeAlreadyExistsException> {
            createUseCase.execute(createCommand(versionCode = 5))
        }
    }

    @Test
    fun `the same versionCode is allowed across two different apps`() {
        createUseCase.execute(createCommand(applicationId = AppTarget.MANAGER.applicationId, versionCode = 3))
        val customerRelease = createUseCase.execute(createCommand(applicationId = AppTarget.CUSTOMER.applicationId, versionCode = 3))
        assertEquals(AppTarget.CUSTOMER, customerRelease.target)
    }

    @Test
    fun `a non-positive versionCode is rejected`() {
        assertThrows<IllegalArgumentException> {
            createUseCase.execute(createCommand(versionCode = 0))
        }
    }

    @Test
    fun `a minSupportedVersionCode above the release's own versionCode is rejected`() {
        assertThrows<IllegalArgumentException> {
            createUseCase.execute(createCommand(versionCode = 2, minSupportedVersionCode = 3))
        }
    }

    // ---------- list: admin and reviewer, never customer ----------

    @Test
    fun `an admin can list every release for an app, including drafts`() {
        createUseCase.execute(createCommand(versionCode = 4, status = AppReleaseStatus.DRAFT))
        val result = listUseCase.execute(ListAppReleasesQuery(admin.id, AppTarget.MANAGER.applicationId))
        assertEquals(1, result.size)
        assertEquals(AppReleaseStatus.DRAFT, result.single().status)
    }

    @Test
    fun `a reviewer can also list releases`() {
        createUseCase.execute(createCommand(versionCode = 6))
        val result = listUseCase.execute(ListAppReleasesQuery(reviewer.id, AppTarget.MANAGER.applicationId))
        assertEquals(1, result.size)
    }

    @Test
    fun `a customer cannot list releases`() {
        assertThrows<PlatformAccessDeniedException> {
            listUseCase.execute(ListAppReleasesQuery(customer.id, AppTarget.MANAGER.applicationId))
        }
    }

    // ---------- update: admin-only, never touches applicationId/versionCode ----------

    @Test
    fun `an admin can update a release's metadata`() {
        // A DRAFT: its artifact (versionName included) is still editable - see the immutability tests below.
        val release = createUseCase.execute(createCommand(versionCode = 7, status = AppReleaseStatus.DRAFT))
        val updated = updateUseCase.execute(
            UpdateAppReleaseCommand(
                callerId = admin.id,
                releaseId = release.id,
                versionName = "1.0.7-hotfix",
                minSupportedVersionCode = release.minSupportedVersionCode,
                isMandatory = true,
                status = AppReleaseStatus.DRAFT,
                downloadUrl = release.downloadUrl,
                sha256 = release.sha256,
                fileSizeBytes = release.fileSizeBytes,
                releaseNotes = "Hotfix notes",
                releaseDate = release.releaseDate,
            ),
        )
        assertEquals("1.0.7-hotfix", updated.versionName)
        assertTrue(updated.isMandatory)
        assertEquals(7, updated.versionCode)
    }

    @Test
    fun `a reviewer cannot update a release`() {
        val release = createUseCase.execute(createCommand(versionCode = 8))
        assertThrows<PlatformAccessDeniedException> {
            updateUseCase.execute(
                UpdateAppReleaseCommand(
                    callerId = reviewer.id,
                    releaseId = release.id,
                    versionName = release.versionName,
                    minSupportedVersionCode = release.minSupportedVersionCode,
                    isMandatory = release.isMandatory,
                    status = release.status,
                    downloadUrl = release.downloadUrl,
                    sha256 = release.sha256,
                    fileSizeBytes = release.fileSizeBytes,
                    releaseNotes = release.releaseNotes,
                    releaseDate = release.releaseDate,
                ),
            )
        }
    }

    @Test
    fun `updating an unknown release id throws`() {
        assertThrows<AppReleaseNotFoundException> {
            updateUseCase.execute(
                UpdateAppReleaseCommand(
                    callerId = admin.id,
                    releaseId = AppReleaseId.new(),
                    versionName = "1.0.0",
                    minSupportedVersionCode = 1,
                    isMandatory = false,
                    status = AppReleaseStatus.DRAFT,
                    downloadUrl = "https://rojanai.ir/x.apk",
                    sha256 = "2898b950da790e12f7197fd0cf30c6543ebff9e929d45c533056f13f2ed7efcd",
                    fileSizeBytes = 1,
                    releaseNotes = null,
                    releaseDate = LocalDate.now(),
                ),
            )
        }
    }

    // ---------- activate / deactivate: admin-only, row is never deleted ----------

    @Test
    fun `an admin can deactivate then reactivate a release`() {
        val release = createUseCase.execute(createCommand(versionCode = 9))
        val deactivated = deactivateUseCase.execute(DeactivateAppReleaseCommand(admin.id, release.id))
        assertFalse(deactivated.isActive)

        val reactivated = activateUseCase.execute(ActivateAppReleaseCommand(admin.id, release.id))
        assertTrue(reactivated.isActive)
    }

    @Test
    fun `a customer cannot deactivate a release`() {
        val release = createUseCase.execute(createCommand(versionCode = 10))
        assertThrows<PlatformAccessDeniedException> {
            deactivateUseCase.execute(DeactivateAppReleaseCommand(customer.id, release.id))
        }
    }

    // ---------- public latest-release lookup: no auth, real version-comparison/mandatory math ----------

    @Test
    fun `no PUBLISHED release yet is a real not-found, never a fabricated no-update response`() {
        assertThrows<AppReleaseNotFoundException> {
            latestUseCase.execute(LatestAppReleaseQuery(AppTarget.RECEPTION.applicationId, 1))
        }
    }

    @Test
    fun `a DRAFT release is invisible to the public lookup - only PUBLISHED counts`() {
        createUseCase.execute(createCommand(versionCode = 11, status = AppReleaseStatus.DRAFT))
        assertThrows<AppReleaseNotFoundException> {
            latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1))
        }
    }

    @Test
    fun `a deactivated PUBLISHED release is also invisible to the public lookup`() {
        val release = createUseCase.execute(createCommand(versionCode = 12))
        deactivateUseCase.execute(DeactivateAppReleaseCommand(admin.id, release.id))
        assertThrows<AppReleaseNotFoundException> {
            latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1))
        }
    }

    @Test
    fun `a caller already on the latest versionCode sees no update available`() {
        createUseCase.execute(createCommand(versionCode = 13))
        val result = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 13))
        assertFalse(result.updateAvailable)
        assertFalse(result.forceUpdate)
    }

    @Test
    fun `a caller behind the latest versionCode sees an optional update when not mandatory`() {
        createUseCase.execute(createCommand(versionCode = 14, minSupportedVersionCode = 1, isMandatory = false))
        val result = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 10))
        assertTrue(result.updateAvailable)
        assertFalse(result.forceUpdate)
        assertEquals(14, result.release.versionCode)
    }

    @Test
    fun `isMandatory forces the update for any caller behind the latest versionCode`() {
        createUseCase.execute(createCommand(versionCode = 15, minSupportedVersionCode = 1, isMandatory = true))
        val result = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 14))
        assertTrue(result.updateAvailable)
        assertTrue(result.forceUpdate)
    }

    @Test
    fun `falling below minSupportedVersionCode forces the update even when isMandatory is false`() {
        createUseCase.execute(createCommand(versionCode = 20, minSupportedVersionCode = 18, isMandatory = false))
        val result = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 17))
        assertTrue(result.updateAvailable)
        assertTrue(result.forceUpdate)
    }

    @Test
    fun `being at or above minSupportedVersionCode but still behind latest is optional, not forced`() {
        createUseCase.execute(createCommand(versionCode = 25, minSupportedVersionCode = 18, isMandatory = false))
        val result = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 20))
        assertTrue(result.updateAvailable)
        assertFalse(result.forceUpdate)
    }

    @Test
    fun `an unknown applicationId on the public lookup is also rejected`() {
        assertThrows<InvalidApplicationIdException> {
            latestUseCase.execute(LatestAppReleaseQuery("com.example.unknown", 1))
        }
    }

    @Test
    fun `the public lookup requires no authorization at all - not even a real user id`() {
        createUseCase.execute(createCommand(versionCode = 30))
        val result = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1))
        assertEquals(30, result.release.versionCode)
    }

    // ---------- App Release Hardening: published artifact immutability ----------

    @Test
    fun `once published, downloadUrl sha256 fileSizeBytes and versionName can no longer change`() {
        val release = createUseCase.execute(createCommand(versionCode = 40))
        val base = unchangedUpdate(release)

        listOf(
            base.copy(downloadUrl = "https://rojanai.ir/downloads/manager/other.apk"),
            base.copy(sha256 = "a".repeat(64)),
            base.copy(fileSizeBytes = release.fileSizeBytes + 1),
            base.copy(versionName = "1.0.40-renamed"),
        ).forEach { command ->
            assertThrows<PublishedAppReleaseArtifactImmutableException> { updateUseCase.execute(command) }
        }

        val stored = requireNotNull(appReleaseRepository.findById(release.id))
        assertEquals("https://rojanai.ir/downloads/manager/ROJAN-AI-Manager-1.0.40.apk", stored.downloadUrl)
        assertEquals("1.0.40", stored.versionName)
    }

    @Test
    fun `a published release keeps its non-artifact metadata editable, and re-sending the same artifact is accepted`() {
        val release = createUseCase.execute(createCommand(versionCode = 41))
        val updated = updateUseCase.execute(
            unchangedUpdate(release).copy(
                sha256 = release.sha256.uppercase(), // same value, different case - normalized, not a change
                isMandatory = true,
                minSupportedVersionCode = 41,
                releaseNotes = "Critical fix",
            ),
        )
        assertTrue(updated.isMandatory)
        assertEquals(41, updated.minSupportedVersionCode)
        assertEquals("Critical fix", updated.releaseNotes)
    }

    @Test
    fun `the artifact stays locked after archiving - archive is not a way to swap the published bytes`() {
        val release = createUseCase.execute(createCommand(versionCode = 42))
        archiveUseCase.execute(ArchiveAppReleaseCommand(admin.id, release.id))
        assertThrows<PublishedAppReleaseArtifactImmutableException> {
            updateUseCase.execute(unchangedUpdate(release, AppReleaseStatus.ARCHIVED).copy(sha256 = "b".repeat(64)))
        }
    }

    @Test
    fun `a draft's artifact can be finalized and published in the same save`() {
        val draft = createUseCase.execute(createCommand(versionCode = 43, status = AppReleaseStatus.DRAFT))
        val published = updateUseCase.execute(
            unchangedUpdate(draft, AppReleaseStatus.PUBLISHED).copy(sha256 = "c".repeat(64), fileSizeBytes = 123L),
        )
        assertEquals(AppReleaseStatus.PUBLISHED, published.status)
        assertEquals("c".repeat(64), published.sha256)
        assertTrue(published.isArtifactLocked)
    }

    // ---------- App Release Hardening: explicit status transitions ----------

    @Test
    fun `DRAFT to PUBLISHED to ARCHIVED to PUBLISHED via the explicit operations`() {
        val draft = createUseCase.execute(createCommand(versionCode = 50, status = AppReleaseStatus.DRAFT))
        assertEquals(AppReleaseStatus.PUBLISHED, publishUseCase.execute(PublishAppReleaseCommand(admin.id, draft.id)).status)
        assertEquals(AppReleaseStatus.ARCHIVED, archiveUseCase.execute(ArchiveAppReleaseCommand(admin.id, draft.id)).status)
        assertEquals(AppReleaseStatus.PUBLISHED, republishUseCase.execute(RepublishAppReleaseCommand(admin.id, draft.id)).status)
    }

    @Test
    fun `an edit may publish a draft or archive a published release`() {
        val draft = createUseCase.execute(createCommand(versionCode = 51, status = AppReleaseStatus.DRAFT))
        val published = updateUseCase.execute(unchangedUpdate(draft, AppReleaseStatus.PUBLISHED))
        assertEquals(AppReleaseStatus.PUBLISHED, published.status)
        val archived = updateUseCase.execute(unchangedUpdate(published, AppReleaseStatus.ARCHIVED))
        assertEquals(AppReleaseStatus.ARCHIVED, archived.status)
    }

    @Test
    fun `an edit can never re-publish an ARCHIVED release - only the explicit republish can`() {
        val release = createUseCase.execute(createCommand(versionCode = 52))
        archiveUseCase.execute(ArchiveAppReleaseCommand(admin.id, release.id))
        assertThrows<InvalidAppReleaseStatusTransitionException> {
            updateUseCase.execute(unchangedUpdate(release, AppReleaseStatus.PUBLISHED))
        }
        assertEquals(AppReleaseStatus.ARCHIVED, requireNotNull(appReleaseRepository.findById(release.id)).status)
    }

    @Test
    fun `disallowed transitions are rejected - back to DRAFT, draft straight to ARCHIVED, and wrong-state operations`() {
        val published = createUseCase.execute(createCommand(versionCode = 53))
        val draft = createUseCase.execute(createCommand(versionCode = 54, status = AppReleaseStatus.DRAFT))

        assertThrows<InvalidAppReleaseStatusTransitionException> { updateUseCase.execute(unchangedUpdate(published, AppReleaseStatus.DRAFT)) }
        assertThrows<InvalidAppReleaseStatusTransitionException> { updateUseCase.execute(unchangedUpdate(draft, AppReleaseStatus.ARCHIVED)) }
        assertThrows<InvalidAppReleaseStatusTransitionException> { publishUseCase.execute(PublishAppReleaseCommand(admin.id, published.id)) }
        assertThrows<InvalidAppReleaseStatusTransitionException> { archiveUseCase.execute(ArchiveAppReleaseCommand(admin.id, draft.id)) }
        assertThrows<InvalidAppReleaseStatusTransitionException> { republishUseCase.execute(RepublishAppReleaseCommand(admin.id, published.id)) }
    }

    @Test
    fun `a rejected status change leaves the rest of the edit unapplied`() {
        val published = createUseCase.execute(createCommand(versionCode = 55))
        assertThrows<InvalidAppReleaseStatusTransitionException> {
            updateUseCase.execute(unchangedUpdate(published, AppReleaseStatus.DRAFT).copy(releaseNotes = "should not stick"))
        }
        assertEquals("Bug fixes", requireNotNull(appReleaseRepository.findById(published.id)).releaseNotes)
    }

    @Test
    fun `a release cannot be created directly as ARCHIVED`() {
        assertThrows<IllegalArgumentException> {
            createUseCase.execute(createCommand(versionCode = 56, status = AppReleaseStatus.ARCHIVED))
        }
    }

    @Test
    fun `publish archive and republish are PLATFORM_ADMIN only`() {
        val draft = createUseCase.execute(createCommand(versionCode = 57, status = AppReleaseStatus.DRAFT))
        assertThrows<PlatformAccessDeniedException> { publishUseCase.execute(PublishAppReleaseCommand(reviewer.id, draft.id)) }
        assertThrows<PlatformAccessDeniedException> { archiveUseCase.execute(ArchiveAppReleaseCommand(customer.id, draft.id)) }
        assertThrows<PlatformAccessDeniedException> { republishUseCase.execute(RepublishAppReleaseCommand(reviewer.id, draft.id)) }
    }

    // ---------- App Release Hardening: HTTPS-only download URLs ----------

    @Test
    fun `only absolute https download URLs are accepted, on create and on edit`() {
        listOf(
            "http://rojanai.ir/downloads/manager/app.apk",
            "ftp://rojanai.ir/app.apk",
            "/downloads/manager/app.apk",
            "https://",
            "not a url",
        ).forEach { url ->
            assertThrows<IllegalArgumentException>(url) { createUseCase.execute(createCommand(versionCode = 60).copy(downloadUrl = url)) }
        }

        val draft = createUseCase.execute(createCommand(versionCode = 61, status = AppReleaseStatus.DRAFT))
        assertThrows<IllegalArgumentException> {
            updateUseCase.execute(unchangedUpdate(draft).copy(downloadUrl = "http://rojanai.ir/downloads/manager/app.apk"))
        }
        val upperCaseScheme = createUseCase.execute(createCommand(versionCode = 62).copy(downloadUrl = "HTTPS://rojanai.ir/app.apk"))
        assertEquals("HTTPS://rojanai.ir/app.apk", upperCaseScheme.downloadUrl)
    }

    // ---------- App Release Hardening: publish audit ----------

    @Test
    fun `publishing records publishedAt and publishedBy, a draft has neither`() {
        val draft = createUseCase.execute(createCommand(versionCode = 70, status = AppReleaseStatus.DRAFT))
        assertNull(draft.publishedAt)
        assertNull(draft.publishedBy)
        assertFalse(draft.isArtifactLocked)

        val published = publishUseCase.execute(PublishAppReleaseCommand(admin.id, draft.id))
        assertNotNull(published.publishedAt)
        assertEquals(admin.id, published.publishedBy)
    }

    @Test
    fun `creating a release directly as PUBLISHED records the creator as publisher`() {
        val release = createUseCase.execute(createCommand(versionCode = 71))
        assertNotNull(release.publishedAt)
        assertEquals(admin.id, release.publishedBy)
    }

    @Test
    fun `republishing re-stamps publishedAt, archiving keeps the last publish on record`() {
        val release = createUseCase.execute(createCommand(versionCode = 72))
        val firstPublishedAt = requireNotNull(release.publishedAt)
        val archived = archiveUseCase.execute(ArchiveAppReleaseCommand(admin.id, release.id))
        assertEquals(firstPublishedAt, archived.publishedAt)

        Thread.sleep(5)
        val republished = republishUseCase.execute(RepublishAppReleaseCommand(admin.id, release.id))
        assertTrue(requireNotNull(republished.publishedAt).isAfter(firstPublishedAt))
        assertEquals(admin.id, republished.publishedBy)
    }

    // ---------- App Release Hardening: channels ----------

    @Test
    fun `a release defaults to the PRODUCTION channel`() {
        assertEquals(AppReleaseChannel.PRODUCTION, createUseCase.execute(createCommand(versionCode = 80)).channel)
    }

    @Test
    fun `the same versionCode may exist once per channel, never twice on one channel`() {
        createUseCase.execute(createCommand(versionCode = 81, channel = AppReleaseChannel.PRODUCTION))
        val beta = createUseCase.execute(createCommand(versionCode = 81, channel = AppReleaseChannel.BETA))
        assertEquals(AppReleaseChannel.BETA, beta.channel)
        assertThrows<AppReleaseVersionCodeAlreadyExistsException> {
            createUseCase.execute(createCommand(versionCode = 81, channel = AppReleaseChannel.BETA))
        }
    }

    @Test
    fun `the public lookup only ever sees the requested channel, PRODUCTION by default`() {
        createUseCase.execute(createCommand(versionCode = 82, channel = AppReleaseChannel.PRODUCTION))
        createUseCase.execute(createCommand(versionCode = 90, channel = AppReleaseChannel.BETA))

        val production = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1))
        assertEquals(82, production.release.versionCode)
        assertEquals(AppReleaseChannel.PRODUCTION, production.release.channel)

        val beta = latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1, AppReleaseChannel.BETA))
        assertEquals(90, beta.release.versionCode)
    }

    @Test
    fun `a channel with nothing published is a real not-found even when another channel has releases`() {
        createUseCase.execute(createCommand(versionCode = 83, channel = AppReleaseChannel.PRODUCTION))
        assertThrows<AppReleaseNotFoundException> {
            latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1, AppReleaseChannel.BETA))
        }
    }

    // ---------- App Release Hardening: ARCHIVED is invisible, archiving rolls back ----------

    @Test
    fun `an ARCHIVED release is invisible to the public lookup`() {
        val release = createUseCase.execute(createCommand(versionCode = 84))
        archiveUseCase.execute(ArchiveAppReleaseCommand(admin.id, release.id))
        assertThrows<AppReleaseNotFoundException> {
            latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1))
        }
    }

    @Test
    fun `archiving the newest published release makes the previous one latest again`() {
        createUseCase.execute(createCommand(versionCode = 85))
        val bad = createUseCase.execute(createCommand(versionCode = 86))
        assertEquals(86, latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1)).release.versionCode)

        archiveUseCase.execute(ArchiveAppReleaseCommand(admin.id, bad.id))
        assertEquals(85, latestUseCase.execute(LatestAppReleaseQuery(AppTarget.MANAGER.applicationId, 1)).release.versionCode)
    }

    // ---------- App Release Hardening: ROJAN Reception versioning ----------

    @Test
    fun `a reception release must use MAJOR MINOR PATCH with the matching versionCode`() {
        val release = createUseCase.execute(receptionCommand("1.0.1", 1_000_001))
        assertEquals(1_000_001, release.versionCode)
        assertEquals(1_002_003, createUseCase.execute(receptionCommand("1.2.3", 1_002_003)).versionCode)
        assertEquals(12_034_056, createUseCase.execute(receptionCommand("12.34.56", 12_034_056)).versionCode)
    }

    @Test
    fun `a reception versionCode that does not match its versionName is rejected`() {
        assertThrows<IllegalArgumentException> { createUseCase.execute(receptionCommand("1.0.1", 2)) }
        assertThrows<IllegalArgumentException> { createUseCase.execute(receptionCommand("1.0.1", 1_000_002)) }
    }

    @Test
    fun `a reception versionName that is not plain MAJOR MINOR PATCH is rejected`() {
        listOf("1.0", "1.0.1.0", "v1.0.1", "1.0.1-beta", "01.0.1", "1.0.1000", " ").forEach { name ->
            assertThrows<IllegalArgumentException>(name) { createUseCase.execute(receptionCommand(name, 1_000_001)) }
        }
    }

    @Test
    fun `a reception draft cannot be renamed to a version its immutable versionCode does not encode`() {
        val draft = createUseCase.execute(receptionCommand("1.0.2", 1_000_002))
        assertThrows<IllegalArgumentException> { updateUseCase.execute(unchangedUpdate(draft).copy(versionName = "1.0.3")) }
    }

    @Test
    fun `the reception version rule does not apply to the Android apps`() {
        val manager = createUseCase.execute(createCommand(versionCode = 3).copy(versionName = "1.0.3-hotfix"))
        assertEquals("1.0.3-hotfix", manager.versionName)
    }

    // ---------- App Release Hardening: signer metadata ----------

    @Test
    fun `signer metadata is optional, normalized, and left unchanged by an edit that does not send it`() {
        val release = createUseCase.execute(
            createCommand(versionCode = 95, status = AppReleaseStatus.DRAFT)
                .copy(signerSubject = "  CN=ROJAN Desktop  ", signerThumbprint = "ab:cd " + "0".repeat(36)),
        )
        assertEquals("CN=ROJAN Desktop", release.signerSubject)
        assertEquals("ABCD" + "0".repeat(36), release.signerThumbprint)

        val edited = updateUseCase.execute(unchangedUpdate(release).copy(releaseNotes = "edited"))
        assertEquals("CN=ROJAN Desktop", edited.signerSubject)
        assertEquals("ABCD" + "0".repeat(36), edited.signerThumbprint)
    }

    @Test
    fun `a malformed signer thumbprint is rejected`() {
        assertThrows<IllegalArgumentException> {
            createUseCase.execute(createCommand(versionCode = 96).copy(signerThumbprint = "not-a-thumbprint"))
        }
    }
}
