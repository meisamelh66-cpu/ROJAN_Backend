package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.media.MediaAssetResponse
import ai.rojan.backend.api.publicsalon.PublicMediaAssetResponse
import ai.rojan.backend.api.salon.AssignSalonIdentityMediaRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.api.schedule.WorkingHoursResponse
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * End-to-end verification of the Salon Identity Foundation media subsystem
 * (Phase A/B/C) against a real (embedded, no-Docker) PostgreSQL and the
 * actual HTTP layer - upload, permission rejection, tenant isolation,
 * public retrieval, and invalid media ownership, per that phase's explicit
 * test requirements.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class MediaFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(): String {
        val email = "media.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Owner", role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    private fun createSalon(token: String, name: String = "Glow Salon"): SalonResponse {
        val response = restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(token)),
            SalonResponse::class.java,
        )
        return requireNotNull(response.body)
    }

    /** Makes [salon] activation-ready and activates it - same recipe used across the other integration tests. */
    private fun activateSalon(token: String, salon: SalonResponse) {
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories"),
                HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(token)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${category.id}/services"),
            HttpMethod.POST,
            HttpEntity(CreateServiceRequest("Haircut", null, 30, BigDecimal("25.00")), bearer(token)),
            ServiceResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"),
            HttpMethod.POST,
            HttpEntity(CreateSpecialistRequest(null, "Stylist", null, null, "+989120000099", "Stylist"), bearer(token)),
            SpecialistResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/${DayOfWeek.MONDAY}"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(token)),
            WorkingHoursResponse::class.java,
        )
        val activate = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"),
            HttpMethod.POST,
            HttpEntity<Void>(bearer(token)),
            SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, activate.statusCode)
    }

    /** A minimal, real JPEG signature (FF D8 FF) followed by padding - the actual bytes the content-sniffing check in `UploadMediaUseCase` validates against, independent of [fileName]/declared Content-Type. */
    private fun jpegBytes(size: Int = 64): ByteArray {
        val bytes = ByteArray(size) { it.toByte() }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        return bytes
    }

    /**
     * [contentType] defaults to `image/jpeg` explicitly rather than letting
     * Spring's form converter infer it from [fileName] - real attacker
     * requests declare a `Content-Type` header independent of whatever
     * filename they send, which is exactly the spoofing scenario the
     * content-sniffing checks below defend against.
     */
    private fun uploadRequestBody(
        fileName: String = "logo.jpg",
        content: ByteArray = jpegBytes(),
        contentType: MediaType = MediaType.IMAGE_JPEG,
    ): MultiValueMap<String, Any> {
        val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
        val resource = object : ByteArrayResource(content) {
            override fun getFilename() = fileName
        }
        val partHeaders = HttpHeaders().apply { this.contentType = contentType }
        body.add("file", HttpEntity(resource, partHeaders))
        return body
    }

    private fun uploadHeaders(token: String) = HttpHeaders().apply {
        setBearerAuth(token)
        contentType = MediaType.MULTIPART_FORM_DATA
    }

    @Test
    fun `owner can upload media for their salon - upload success`() {
        val token = registerAndLogin()
        val salon = createSalon(token)

        val upload = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media?mediaType=GALLERY"),
            HttpMethod.POST,
            HttpEntity(uploadRequestBody(), uploadHeaders(token)),
            MediaAssetResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, upload.statusCode)
        val mediaAsset = requireNotNull(upload.body)
        assertEquals(salon.id, mediaAsset.salonId)
        assertTrue(mediaAsset.url.contains("/media/salon/${salon.id}/"))

        val list = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            Array<MediaAssetResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, list.statusCode)
        assertTrue(list.body!!.any { it.id == mediaAsset.id })
    }

    @Test
    fun `storage url extension is derived from real file content, not the client filename - spoofed extension defense`() {
        val token = registerAndLogin()
        val salon = createSalon(token)

        val upload = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media?mediaType=GALLERY"),
            HttpMethod.POST,
            HttpEntity(uploadRequestBody(fileName = "evil.html"), uploadHeaders(token)),
            MediaAssetResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, upload.statusCode)
        val mediaAsset = requireNotNull(upload.body)
        assertTrue(mediaAsset.url.endsWith(".jpg"), "expected a .jpg url derived from the real JPEG bytes, got: ${mediaAsset.url}")
    }

    @Test
    fun `upload is rejected when actual file content does not match the declared type - spoofed content-type`() {
        val token = registerAndLogin()
        val salon = createSalon(token)
        val notActuallyAnImage = "<script>alert(1)</script>".toByteArray()

        val upload = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media?mediaType=GALLERY"),
            HttpMethod.POST,
            HttpEntity(uploadRequestBody(fileName = "evil.html", content = notActuallyAnImage), uploadHeaders(token)),
            String::class.java,
        )

        assertEquals(HttpStatus.BAD_REQUEST, upload.statusCode)
        assertTrue(upload.body!!.contains("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    fun `a caller who does not own the salon cannot upload media - permission rejection`() {
        val ownerToken = registerAndLogin()
        val salon = createSalon(ownerToken)
        val strangerToken = registerAndLogin()

        val upload = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media?mediaType=GALLERY"),
            HttpMethod.POST,
            HttpEntity(uploadRequestBody(), uploadHeaders(strangerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, upload.statusCode)
    }

    @Test
    fun `deleting media through a different salon's path is rejected - tenant isolation`() {
        val token = registerAndLogin()
        val salonA = createSalon(token, "Salon A")
        val salonB = createSalon(token, "Salon B")

        val mediaOnA = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salonA.id}/media?mediaType=GALLERY"),
                HttpMethod.POST,
                HttpEntity(uploadRequestBody(), uploadHeaders(token)),
                MediaAssetResponse::class.java,
            ).body,
        )

        // Same owner, but the media id belongs to Salon A, not Salon B - the
        // path's salonId must still be authoritative.
        val delete = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/media/${mediaOnA.id}"),
            HttpMethod.DELETE,
            HttpEntity<Void>(bearer(token)),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, delete.statusCode)
        // And it must still exist, untouched, under its real salon.
        val stillThere = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/media"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            Array<MediaAssetResponse>::class.java,
        )
        assertTrue(stillThere.body!!.any { it.id == mediaOnA.id })
    }

    @Test
    fun `assigning another salon's media as identity is rejected - invalid media ownership`() {
        val token = registerAndLogin()
        val salonA = createSalon(token, "Salon A")
        val salonB = createSalon(token, "Salon B")

        val mediaOnA = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salonA.id}/media?mediaType=LOGO"),
                HttpMethod.POST,
                HttpEntity(uploadRequestBody(), uploadHeaders(token)),
                MediaAssetResponse::class.java,
            ).body,
        )

        val assign = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/identity-media"),
            HttpMethod.PUT,
            HttpEntity(AssignSalonIdentityMediaRequest(logoMediaId = mediaOnA.id, coverMediaId = null), bearer(token)),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, assign.statusCode)
        assertTrue(assign.body!!.contains("MEDIA_TENANT_MISMATCH"))
    }

    @Test
    fun `public gallery returns an active salon's gallery media without authentication - public retrieval`() {
        val token = registerAndLogin()
        val salon = createSalon(token)
        activateSalon(token, salon)

        val uploaded = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/media?mediaType=GALLERY"),
                HttpMethod.POST,
                HttpEntity(uploadRequestBody(), uploadHeaders(token)),
                MediaAssetResponse::class.java,
            ).body,
        )

        val gallery = restTemplate.exchange(
            url("/api/v1/public/salons/${salon.slug}/gallery"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Array<PublicMediaAssetResponse>::class.java,
        )

        assertEquals(HttpStatus.OK, gallery.statusCode)
        assertTrue(gallery.body!!.any { it.id == uploaded.id })
    }

    @Test
    fun `public gallery 404s for a DRAFT salon - never distinguishable from unknown`() {
        val token = registerAndLogin()
        val salon = createSalon(token) // never activated

        val gallery = restTemplate.exchange(
            url("/api/v1/public/salons/${salon.slug}/gallery"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, gallery.statusCode)
    }
}
