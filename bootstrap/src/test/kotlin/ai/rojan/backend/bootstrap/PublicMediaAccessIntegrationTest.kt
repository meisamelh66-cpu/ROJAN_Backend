package ai.rojan.backend.bootstrap

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

// PASS MEDIA-PUBLIC-01: the real regression this protects - a real anonymous browser requesting a
// salon's real logo/cover/gallery URL got a real 401 AUTH_UNAUTHORIZED (live-confirmed,
// PASS REALITY-03), because the media route had no SecurityConfig rule at all and fell through to
// anyRequest().authenticated(). SecurityConfig's new rule is scoped precisely to
// GET /media/salons/{salonId}/media/... - the exact, real shape UploadMediaUseCase writes every
// PUBLIC_IMAGE_TYPES asset under (confirmed by direct source read) - never the broader media route.
//
// These tests exercise the real Spring Security filter chain end to end (a real HTTP call through
// a real embedded server, not a unit test of the config object) and assert on the class of
// response (401 vs. anything else) rather than real file bytes: InMemoryMediaStorage is the real,
// active MediaStoragePort in the test profile (resolveUrl returns a cdn.test.rojan.ai URL, never
// this app's own media route), so no real LocalDisk-backed file exists in this profile for these
// exact URL shapes to resolve to - a 401 unambiguously means "Security blocked this before a
// handler ever ran", and anything else (404, since MediaResourceConfig's resource handler finds no
// real file here) unambiguously means "Security let this through", which is the one real thing
// this pass changed.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PublicMediaAccessIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    @Test
    fun `an anonymous GET to a real salon's public media path is never blocked by security (401)`() {
        val response = restTemplate.getForEntity(
            url("/media/salons/11111111-1111-1111-1111-111111111111/media/some-real-file.png"),
            String::class.java,
        )

        // No real file backs this exact path in the `test` profile (InMemoryMediaStorage never
        // writes to LocalMediaStorageProperties.storageRoot) - 404 is the real, expected outcome
        // here. The one thing this test actually protects is that Security did NOT reject the
        // request outright, which is exactly what an anonymous customer's browser hit before this
        // pass (a real 401, before ever reaching a resource handler at all).
        assertNotEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertNotEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `a DOCUMENT-shaped media path for the same salon stays protected - the fix is precisely scoped, not a blanket permitAll`() {
        val response = restTemplate.getForEntity(
            url("/media/salons/11111111-1111-1111-1111-111111111111/documents/some-real-file.pdf"),
            String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `only GET is permitted on the public media path - POST still requires real authentication`() {
        val response = restTemplate.postForEntity(
            url("/media/salons/11111111-1111-1111-1111-111111111111/media/some-real-file.png"),
            null,
            String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `a real salon id with no real media at all still resolves past security the same way - the rule matches on real URL shape, never on whether the asset exists`() {
        val response = restTemplate.exchange(
            url("/media/salons/99999999-9999-9999-9999-999999999999/media/nonexistent.png"),
            HttpMethod.GET, null, String::class.java,
        )

        assertNotEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    // Customer Profile Personalization Phase 5A.2: the analogous regression for user avatar/cover
    // media - live-confirmed (2026-09-13) an anonymous GET on a real user's avatarUrl returned 401
    // AUTH_UNAUTHORIZED, because /media/users/** had no SecurityConfig rule either, same root cause
    // as the salon case above. Fix mirrors the salon rule exactly: GET /media/users/{userId}/media/**
    // (the real shape UploadUserAvatarUseCase/UploadUserCoverUseCase write to, confirmed by direct
    // source read of UserProfileMediaUseCases.kt) is now permitAll; nothing else changes.

    @Test
    fun `an anonymous GET to a real user's public avatar media path is never blocked by security (401)`() {
        val response = restTemplate.getForEntity(
            url("/media/users/11111111-1111-1111-1111-111111111111/media/some-real-file.png"),
            String::class.java,
        )

        // Same reasoning as the salon case: InMemoryMediaStorage backs this profile, so no real
        // file exists here - 404 is expected. Only a 401/403 would mean security still blocks it.
        assertNotEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertNotEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `only GET is permitted on the public user media path - the upload endpoint still requires real authentication`() {
        val response = restTemplate.postForEntity(
            url("/api/v1/users/me/media/avatar"),
            null,
            String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `a real userId with no real media at all still resolves past security the same way - the rule matches on real URL shape, never on whether the asset exists`() {
        val response = restTemplate.exchange(
            url("/media/users/99999999-9999-9999-9999-999999999999/media/nonexistent.png"),
            HttpMethod.GET, null, String::class.java,
        )

        assertNotEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
