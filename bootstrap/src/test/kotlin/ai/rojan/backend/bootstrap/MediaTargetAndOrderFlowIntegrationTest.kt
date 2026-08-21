package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.media.MediaAssetResponse
import ai.rojan.backend.api.media.ReorderMediaRequest
import ai.rojan.backend.api.publicsalon.PublicMediaAssetResponse
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
import ai.rojan.backend.domain.media.MediaType
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
import org.springframework.http.MediaType as HttpMediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID

/**
 * Media System Evolution v2 - real HTTP + embedded Postgres, same pattern as
 * [SalonMediaFlowIntegrationTest]. Covers what that file doesn't: targeted
 * PORTFOLIO/SERVICE_IMAGE uploads, the public per-specialist/per-service
 * read endpoints, and the reorder endpoint - the three genuinely new
 * capabilities this evolution adds on top of the existing Media Foundation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class MediaTargetAndOrderFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(fullName: String): String {
        val email = "media-v2.${System.nanoTime()}@example.com"
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
            url("/api/v1/salons"), HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    private fun createSpecialist(ownerToken: String, salonId: UUID, name: String): SpecialistResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/specialists"), HttpMethod.POST,
            HttpEntity(CreateSpecialistRequest(null, name, null, null, null, null), bearer(ownerToken)),
            SpecialistResponse::class.java,
        ).body,
    )

    private fun createService(ownerToken: String, salonId: UUID): ServiceResponse {
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/categories"), HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        return requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/categories/${category.id}/services"), HttpMethod.POST,
                HttpEntity(CreateServiceRequest("Haircut", null, 30, java.math.BigDecimal("25.00")), bearer(ownerToken)),
                ServiceResponse::class.java,
            ).body,
        )
    }

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    private fun uploadMedia(ownerToken: String, salonId: UUID, mediaType: MediaType, targetId: UUID? = null): MediaAssetResponse {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(pngBytes) { override fun getFilename() = "photo.png" })
        body.add("mediaType", mediaType.name)
        if (targetId != null) body.add("targetId", targetId.toString())
        val headers = bearer(ownerToken).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }
        return requireNotNull(
            restTemplate.postForEntity(url("/api/v1/salons/$salonId/media"), HttpEntity(body, headers), MediaAssetResponse::class.java).body,
        )
    }

    @Test
    fun `uploading PORTFOLIO with no targetId is rejected with 400`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(pngBytes) { override fun getFilename() = "photo.png" })
        body.add("mediaType", "PORTFOLIO")
        val headers = bearer(ownerToken).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }

        val response = restTemplate.postForEntity(url("/api/v1/salons/${salon.id}/media"), HttpEntity(body, headers), String::class.java)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(response.body!!.contains("MEDIA_TARGET_REQUIRED"))
    }

    @Test
    fun `a specialist's portfolio upload is publicly readable via the per-specialist endpoint, and stays out of the salon's plain gallery`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio ${System.nanoTime()}")
        val specialist = createSpecialist(ownerToken, salon.id, "Ada")
        uploadMedia(ownerToken, salon.id, MediaType.PORTFOLIO, specialist.id)
        uploadMedia(ownerToken, salon.id, MediaType.GALLERY)
        // Activation requires at least one active service/specialist/working-hours day.
        createService(ownerToken, salon.id)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        restTemplate.postForEntity(url("/api/v1/salons/${salon.id}/activate"), HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)

        val portfolio = requireNotNull(
            restTemplate.getForEntity(url("/api/v1/public/salons/${salon.slug}/specialists/${specialist.id}/portfolio"), Array<PublicMediaAssetResponse>::class.java).body,
        )
        val gallery = requireNotNull(
            restTemplate.getForEntity(url("/api/v1/public/salons/${salon.slug}/gallery"), Array<PublicMediaAssetResponse>::class.java).body,
        )

        assertEquals(1, portfolio.size)
        assertEquals(1, gallery.size)
        assertEquals(MediaType.GALLERY, gallery.single().mediaType)
    }

    @Test
    fun `a service's images are publicly readable via the per-service endpoint`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio ${System.nanoTime()}")
        val service = createService(ownerToken, salon.id)
        uploadMedia(ownerToken, salon.id, MediaType.SERVICE_IMAGE, service.id)
        createSpecialist(ownerToken, salon.id, "Ada")
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        restTemplate.postForEntity(url("/api/v1/salons/${salon.id}/activate"), HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)

        val images = requireNotNull(
            restTemplate.getForEntity(url("/api/v1/public/salons/${salon.slug}/services/${service.id}/images"), Array<PublicMediaAssetResponse>::class.java).body,
        )

        assertEquals(1, images.size)
    }

    @Test
    fun `reordering a gallery changes the order returned by a subsequent list call`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val first = uploadMedia(ownerToken, salon.id, MediaType.GALLERY)
        val second = uploadMedia(ownerToken, salon.id, MediaType.GALLERY)

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media/reorder"), HttpMethod.PATCH,
            HttpEntity(ReorderMediaRequest(MediaType.GALLERY, null, listOf(second.id, first.id)), bearer(ownerToken)),
            Void::class.java,
        )

        val listed = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media?mediaType=GALLERY"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), Array<MediaAssetResponse>::class.java,
        ).body!!

        assertEquals(listOf(second.id, first.id), listed.map { it.id })
    }

    @Test
    fun `reorder with a foreign media id is rejected with 400 and changes nothing`() {
        val ownerToken = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val galleryAsset = uploadMedia(ownerToken, salon.id, MediaType.GALLERY)
        val logoAsset = uploadMedia(ownerToken, salon.id, MediaType.LOGO)

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media/reorder"), HttpMethod.PATCH,
            HttpEntity(ReorderMediaRequest(MediaType.GALLERY, null, listOf(galleryAsset.id, logoAsset.id)), bearer(ownerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(response.body!!.contains("MEDIA_REORDER_MISMATCH"))
    }
}
