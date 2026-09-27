package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.common.GlobalExceptionHandler
import ai.rojan.backend.domain.apprelease.AppRelease
import ai.rojan.backend.domain.apprelease.AppReleaseChannel
import ai.rojan.backend.domain.apprelease.AppReleaseRepository
import ai.rojan.backend.domain.apprelease.AppReleaseStatus
import ai.rojan.backend.domain.apprelease.AppTarget
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.AppReleaseConcurrentModificationException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.context.request.ServletWebRequest
import java.time.LocalDate

/**
 * App Release optimistic concurrency (B1), against the real embedded Postgres so the real
 * `@Version` column from V43 and the real repository adapter are exercised: two copies of the same
 * release are loaded, the first save wins, and a save of the now-stale second copy is refused with
 * [AppReleaseConcurrentModificationException] instead of silently overwriting the newer row -
 * including the case where the first save published the release. Each test uses its own channel/
 * versionCode so rows from other tests in the shared database never collide.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class AppReleaseConcurrencyIntegrationTest {

    @Autowired
    private lateinit var appReleaseRepository: AppReleaseRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private fun seedAdmin(): User =
        userRepository.save(User.registerWithPhone(PhoneNumber("+9894${(1_000_000..9_999_999).random()}"), "Concurrency Admin", UserRole.PLATFORM_ADMIN))

    private fun saveDraft(admin: User, versionCode: Int): AppRelease =
        appReleaseRepository.save(
            AppRelease.create(
                target = AppTarget.MANAGER,
                versionName = "9.0.$versionCode",
                versionCode = versionCode,
                minSupportedVersionCode = 1,
                isMandatory = false,
                status = AppReleaseStatus.DRAFT,
                downloadUrl = "https://rojanai.ir/download/test/$versionCode.apk",
                sha256 = "a".repeat(64),
                fileSizeBytes = 1_000L,
                releaseNotes = "original",
                releaseDate = LocalDate.of(2026, 9, 27),
                isActive = true,
                createdBy = admin.id,
                channel = AppReleaseChannel.BETA,
            ),
        )

    private fun AppRelease.editedCopy(notes: String, sha256: String = this.sha256) = apply {
        updateMetadata(
            versionName = versionName,
            minSupportedVersionCode = minSupportedVersionCode,
            isMandatory = isMandatory,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            fileSizeBytes = fileSizeBytes,
            releaseNotes = notes,
            releaseDate = releaseDate,
        )
    }

    @Test
    fun `two copies of one release - the first save succeeds, the stale second save is refused`() {
        val admin = seedAdmin()
        val saved = saveDraft(admin, 9_101)
        assertEquals(0L, saved.version)

        val first = requireNotNull(appReleaseRepository.findById(saved.id))
        val second = requireNotNull(appReleaseRepository.findById(saved.id))

        val afterFirst = appReleaseRepository.save(first.editedCopy("first wins"))
        assertEquals(1L, afterFirst.version)

        assertThrows<AppReleaseConcurrentModificationException> {
            appReleaseRepository.save(second.editedCopy("stale second"))
        }

        val stored = requireNotNull(appReleaseRepository.findById(saved.id))
        assertEquals("first wins", stored.releaseNotes)
        assertEquals(1L, stored.version)
    }

    @Test
    fun `a stale draft copy can never overwrite a release that was published in the meantime`() {
        val admin = seedAdmin()
        val saved = saveDraft(admin, 9_102)

        val publisherCopy = requireNotNull(appReleaseRepository.findById(saved.id))
        val staleDraftCopy = requireNotNull(appReleaseRepository.findById(saved.id))

        publisherCopy.publish(admin.id)
        appReleaseRepository.save(publisherCopy)

        // The stale copy still believes it is an unlocked DRAFT, so the domain lets it change the
        // artifact - persistence must refuse it, or the published release would silently revert.
        assertNull(staleDraftCopy.publishedAt)
        assertThrows<AppReleaseConcurrentModificationException> {
            appReleaseRepository.save(staleDraftCopy.editedCopy("stale", sha256 = "b".repeat(64)))
        }

        val stored = requireNotNull(appReleaseRepository.findById(saved.id))
        assertEquals(AppReleaseStatus.PUBLISHED, stored.status)
        assertEquals("a".repeat(64), stored.sha256)
        assertEquals("original", stored.releaseNotes)
    }

    @Test
    fun `a fresh copy loaded after the conflict saves normally`() {
        val admin = seedAdmin()
        val saved = saveDraft(admin, 9_103)
        val stale = requireNotNull(appReleaseRepository.findById(saved.id))
        appReleaseRepository.save(requireNotNull(appReleaseRepository.findById(saved.id)).editedCopy("newer"))

        assertThrows<AppReleaseConcurrentModificationException> { appReleaseRepository.save(stale.editedCopy("stale")) }

        val reloaded = requireNotNull(appReleaseRepository.findById(saved.id))
        val retried = appReleaseRepository.save(reloaded.editedCopy("retried"))
        assertEquals("retried", retried.releaseNotes)
        assertEquals(2L, retried.version)
    }

    @Test
    fun `the conflict is an API 409 with its own error code, never a 500`() {
        val response = GlobalExceptionHandler().handleConflict(
            AppReleaseConcurrentModificationException("release-id"),
            ServletWebRequest(MockHttpServletRequest("PUT", "/api/v1/platform-authority/app-releases/release-id")),
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("APP_RELEASE_CONCURRENT_MODIFICATION", requireNotNull(response.body).errorCode)
    }
}
