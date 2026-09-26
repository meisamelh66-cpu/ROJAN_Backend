package ai.rojan.backend.application.apprelease

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.apprelease.AppRelease
import ai.rojan.backend.domain.apprelease.AppReleaseId
import ai.rojan.backend.domain.apprelease.AppReleaseRepository
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import ai.rojan.backend.domain.apprelease.AppTarget
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.AppReleaseNotFoundException
import ai.rojan.backend.domain.common.AppReleaseVersionCodeAlreadyExistsException
import ai.rojan.backend.domain.common.InvalidApplicationIdException
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    override fun findLatestPublished(target: AppTarget): AppRelease? =
        store.values.filter { it.target == target && it.status == AppReleaseStatus.PUBLISHED && it.isActive }
            .maxByOrNull { it.versionCode }
    override fun existsByTargetAndVersionCode(target: AppTarget, versionCode: Int): Boolean =
        store.values.any { it.target == target && it.versionCode == versionCode }
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
    )

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
        val release = createUseCase.execute(createCommand(versionCode = 7))
        val updated = updateUseCase.execute(
            UpdateAppReleaseCommand(
                callerId = admin.id,
                releaseId = release.id,
                versionName = "1.0.7-hotfix",
                minSupportedVersionCode = release.minSupportedVersionCode,
                isMandatory = true,
                status = AppReleaseStatus.PUBLISHED,
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
}
