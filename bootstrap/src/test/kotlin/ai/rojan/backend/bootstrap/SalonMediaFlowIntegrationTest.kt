package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.media.MediaAssetResponse
import ai.rojan.backend.api.salon.AssignIdentityMediaRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.media.MediaAssetStatus
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.UUID

/**
 * Media Foundation (Phase 1) - real HTTP + embedded Postgres, same pattern
 * as every other file in this directory. Exercises upload -> assign ->
 * read-back-resolves-a-url, re-assignment archiving (not deleting) the
 * previous asset, delete clearing a live identity-slot assignment, and
 * cross-salon tenant isolation. Uploads a tiny real PNG's byte signature,
 * not a text stub, since [ai.rojan.backend.application.media.UploadMediaUseCase]
 * only validates declared mime type today - a genuinely wrong-content
 * upload isn't yet distinguishable here, worth a follow-up if magic-byte
 * sniffing is added later.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonMediaFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(fullName: String): String {
        val email = "media.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = fullName, role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    private fun createSalon(ownerToken: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    // Minimal valid PNG signature + IHDR-shaped filler - enough bytes to be a non-trivial "file", not a real decodable image.
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    private fun uploadMedia(ownerToken: String, salonId: UUID, mediaType: MediaType, filename: String = "logo.png"): MediaAssetResponse {
        val body = LinkedMultiValueMap<String, Any>()
        body.add(
            "file",
            object : ByteArrayResource(pngBytes) {
                override fun getFilename() = filename
            },
        )
        body.add("mediaType", mediaType.name)
        val headers = bearer(ownerToken).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }
        return requireNotNull(
            restTemplate.postForEntity(url("/api/v1/salons/$salonId/media"), HttpEntity(body, headers), MediaAssetResponse::class.java).body,
        )
    }

    @Test
    fun `owner uploads a logo, assigns it, and the salon response resolves a real url from the stored id`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val uploaded = uploadMedia(ownerToken, salon.id, MediaType.LOGO)
        assertEquals(MediaAssetStatus.ACTIVE, uploaded.status)
        assertTrue(uploaded.url.isNotBlank())

        val assigned = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/identity-media"), HttpMethod.PUT,
            HttpEntity(AssignIdentityMediaRequest(IdentitySlot.LOGO, uploaded.id), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body!!

        assertEquals(uploaded.id, assigned.logoMediaId)
        assertEquals(uploaded.url, assigned.logoUrl)
    }

    @Test
    fun `re-assigning the logo slot archives the previous asset, not deletes it`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val first = uploadMedia(ownerToken, salon.id, MediaType.LOGO)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/identity-media"), HttpMethod.PUT,
            HttpEntity(AssignIdentityMediaRequest(IdentitySlot.LOGO, first.id), bearer(ownerToken)), SalonResponse::class.java,
        )
        val second = uploadMedia(ownerToken, salon.id, MediaType.LOGO)

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/identity-media"), HttpMethod.PUT,
            HttpEntity(AssignIdentityMediaRequest(IdentitySlot.LOGO, second.id), bearer(ownerToken)), SalonResponse::class.java,
        )

        val list = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), Array<MediaAssetResponse>::class.java,
        ).body!!.toList()
        val previous = list.first { it.id == first.id }
        assertEquals(MediaAssetStatus.ARCHIVED, previous.status)
    }

    @Test
    fun `deleting the currently-assigned logo clears the salon's slot in the same call`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val uploaded = uploadMedia(ownerToken, salon.id, MediaType.COVER)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/identity-media"), HttpMethod.PUT,
            HttpEntity(AssignIdentityMediaRequest(IdentitySlot.COVER, uploaded.id), bearer(ownerToken)), SalonResponse::class.java,
        )

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media/${uploaded.id}"), HttpMethod.DELETE,
            HttpEntity<Void>(bearer(ownerToken)), Void::class.java,
        )

        val salonAfter = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        ).body!!
        assertNull(salonAfter.coverMediaId)
        assertNull(salonAfter.coverImageUrl)
    }

    @Test
    fun `assigning a gallery image to the logo slot is rejected as a type mismatch`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val galleryAsset = uploadMedia(ownerToken, salon.id, MediaType.GALLERY)

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/identity-media"), HttpMethod.PUT,
            HttpEntity(AssignIdentityMediaRequest(IdentitySlot.LOGO, galleryAsset.id), bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertTrue(response.body!!.contains("MEDIA_TYPE_MISMATCH"))
    }

    @Test
    fun `assigning media that belongs to a different salon 404s, not 403`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val assetOnA = uploadMedia(ownerToken, salonA.id, MediaType.LOGO)

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/identity-media"), HttpMethod.PUT,
            HttpEntity(AssignIdentityMediaRequest(IdentitySlot.LOGO, assetOnA.id), bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `a caller with no membership at the salon cannot upload media`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val strangerToken = registerAndLogin("Someone Else")

        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(pngBytes) { override fun getFilename() = "logo.png" })
        body.add("mediaType", "LOGO")
        val headers = bearer(strangerToken).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }

        val response = restTemplate.postForEntity(url("/api/v1/salons/${salon.id}/media"), HttpEntity(body, headers), String::class.java)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }
}
