package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.media.MediaAssetResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType as HttpMediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

/**
 * Phase 5A.2, User Profile Media - end-to-end verification of the
 * USER-owned media path (avatar / profile cover) against a real embedded
 * PostgreSQL and the actual HTTP layer, built on the canonical Media
 * System Evolution v2 foundation already covered by
 * [SalonMediaFlowIntegrationTest], plus an explicit regression check that
 * the SALON media flow still works unchanged alongside it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class UserProfileMediaFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"
    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole = UserRole.CUSTOMER): String {
        val email = "profile.media.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Gita", role = role),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    // Real PNG magic bytes (padded) - content sniffing validates actual bytes now.
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    private fun imageBody(fileName: String): MultiValueMap<String, Any> {
        val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
        body.add(
            "file",
            object : ByteArrayResource(pngBytes) {
                override fun getFilename() = fileName
            },
        )
        return body
    }

    private fun uploadHeaders(token: String) = bearer(token).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }

    private fun me(token: String): UserResponse = requireNotNull(
        restTemplate.exchange(url("/api/v1/users/me"), HttpMethod.GET, HttpEntity<Void>(bearer(token)), UserResponse::class.java).body,
    )

    private fun postAvatar(token: String, fileName: String = "avatar.png") = restTemplate.exchange(
        url("/api/v1/users/me/media/avatar"), HttpMethod.POST, HttpEntity(imageBody(fileName), uploadHeaders(token)), UserResponse::class.java,
    )

    private fun postCover(token: String, fileName: String = "cover.png") = restTemplate.exchange(
        url("/api/v1/users/me/media/cover"), HttpMethod.POST, HttpEntity(imageBody(fileName), uploadHeaders(token)), UserResponse::class.java,
    )

    @Test
    fun `user avatar upload success - GET me returns the resolved avatarUrl`() {
        val token = registerAndLogin()
        assertNull(me(token).avatarUrl, "no avatar before upload")

        val upload = postAvatar(token)
        assertEquals(HttpStatus.OK, upload.statusCode)
        val body = requireNotNull(upload.body)
        assertNotNull(body.avatarUrl)
        assertNull(body.coverUrl)

        assertEquals(body.avatarUrl, me(token).avatarUrl)
    }

    @Test
    fun `user cover upload success - independent of the avatar slot`() {
        val token = registerAndLogin()

        val avatarUrl = requireNotNull(postAvatar(token).body).avatarUrl
        val afterCover = requireNotNull(postCover(token).body)

        assertNotNull(afterCover.coverUrl)
        assertEquals(avatarUrl, afterCover.avatarUrl, "uploading a cover must not disturb the avatar")
    }

    @Test
    fun `each caller's media is isolated to their own account`() {
        val tokenA = registerAndLogin()
        val tokenB = registerAndLogin()

        val a = requireNotNull(postAvatar(tokenA, "a.png").body)
        val b = requireNotNull(postAvatar(tokenB, "b.png").body)

        assertFalse(a.id == b.id)
        assertFalse(a.avatarUrl == b.avatarUrl)
        assertEquals(a.avatarUrl, me(tokenA).avatarUrl)
        assertEquals(b.avatarUrl, me(tokenB).avatarUrl)
    }

    @Test
    fun `delete avatar works - and is idempotent`() {
        val token = registerAndLogin()
        postAvatar(token)
        assertNotNull(me(token).avatarUrl)

        val del = restTemplate.exchange(
            url("/api/v1/users/me/media/avatar"), HttpMethod.DELETE, HttpEntity<Void>(bearer(token)), UserResponse::class.java,
        )
        assertEquals(HttpStatus.OK, del.statusCode)
        assertNull(requireNotNull(del.body).avatarUrl)
        assertNull(me(token).avatarUrl)

        val del2 = restTemplate.exchange(
            url("/api/v1/users/me/media/avatar"), HttpMethod.DELETE, HttpEntity<Void>(bearer(token)), UserResponse::class.java,
        )
        assertEquals(HttpStatus.OK, del2.statusCode)
    }

    @Test
    fun `re-uploading an avatar replaces the previous URL`() {
        val token = registerAndLogin()
        val first = requireNotNull(postAvatar(token, "first.png").body).avatarUrl
        val second = requireNotNull(postAvatar(token, "second.png").body).avatarUrl

        assertFalse(first == second)
        assertEquals(second, me(token).avatarUrl)
    }

    @Test
    fun `an unauthenticated caller cannot touch profile media`() {
        val anon = restTemplate.exchange(
            url("/api/v1/users/me/media/avatar"), HttpMethod.POST,
            HttpEntity(imageBody("x.png"), HttpHeaders().apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, anon.statusCode)
    }

    @Test
    fun `salon media regression - the SALON upload and list flow is unchanged alongside user media`() {
        val token = registerAndLogin(role = UserRole.MANAGER)

        assertNotNull(requireNotNull(postAvatar(token).body).avatarUrl)

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"), HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Glow Salon", null, "+1 555 0100", null, "1 Main St"), bearer(token)),
                SalonResponse::class.java,
            ).body,
        )
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(pngBytes) { override fun getFilename() = "gallery.png" })
        body.add("mediaType", "GALLERY")
        val salonMedia = restTemplate.postForEntity(
            url("/api/v1/salons/${salon.id}/media"), HttpEntity(body, uploadHeaders(token)), MediaAssetResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, salonMedia.statusCode)
        val asset = requireNotNull(salonMedia.body)
        assertEquals(salon.id, asset.salonId)

        val list = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media"), HttpMethod.GET, HttpEntity<Void>(bearer(token)), Array<MediaAssetResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, list.statusCode)
        assertTrue(list.body!!.any { it.id == asset.id })
    }
}
