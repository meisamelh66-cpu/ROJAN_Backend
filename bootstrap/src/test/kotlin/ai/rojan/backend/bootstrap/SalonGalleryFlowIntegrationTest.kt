package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.GalleryImageResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.salon.GalleryImage
import ai.rojan.backend.domain.salon.GalleryImageRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * D6 (Build now): end-to-end verification of Customer's real read access to a salon's gallery,
 * against a real (embedded, no-Docker) PostgreSQL and the actual HTTP layer - mirroring
 * `SalonManagementFlowIntegrationTest`'s own shape. No upload endpoint exists yet (see
 * `GalleryController`'s own doc comment), so rows are seeded directly via the real
 * [GalleryImageRepository] bean, not a fabricated stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonGalleryFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var galleryImageRepository: GalleryImageRepository

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(): String {
        val email = "gallery.${System.nanoTime()}@example.com"
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

    private fun createSalon(token: String): SalonResponse {
        val response = restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(
                CreateSalonRequest("Glow Salon", "Full service", "+1 555 0100", "hello@glow.example", "1 Main St"),
                bearer(token),
            ),
            SalonResponse::class.java,
        )
        return requireNotNull(response.body)
    }

    @Test
    fun `an authenticated caller sees a salon's gallery images in display order`() {
        val token = registerAndLogin()
        val salon = createSalon(token)

        // Seeded out of order on purpose - the endpoint's own ordering must correct it, not
        // whatever order the rows happen to be inserted in.
        galleryImageRepository.save(GalleryImage.create(SalonId(salon.id), "https://cdn.example.com/b.jpg", displayOrder = 1))
        galleryImageRepository.save(GalleryImage.create(SalonId(salon.id), "https://cdn.example.com/a.jpg", displayOrder = 0))

        val gallery = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/gallery"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            Array<GalleryImageResponse>::class.java,
        )

        assertEquals(HttpStatus.OK, gallery.statusCode)
        val images = requireNotNull(gallery.body).toList()
        assertEquals(2, images.size)
        assertEquals("https://cdn.example.com/a.jpg", images[0].url)
        assertEquals("https://cdn.example.com/b.jpg", images[1].url)
        assertTrue(images.all { it.salonId == salon.id })
    }

    @Test
    fun `a salon with no gallery images returns a real empty list, never an error`() {
        val token = registerAndLogin()
        val salon = createSalon(token)

        val gallery = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/gallery"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            Array<GalleryImageResponse>::class.java,
        )

        assertEquals(HttpStatus.OK, gallery.statusCode)
        assertTrue(requireNotNull(gallery.body).isEmpty())
    }

    @Test
    fun `an unauthenticated caller is rejected with 401, never shown gallery data`() {
        val token = registerAndLogin()
        val salon = createSalon(token)
        galleryImageRepository.save(GalleryImage.create(SalonId(salon.id), "https://cdn.example.com/a.jpg", displayOrder = 0))

        val gallery = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/gallery"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, gallery.statusCode)
    }

    @Test
    fun `an unknown salon id returns a real empty list, never fabricated data or a crash`() {
        val token = registerAndLogin()

        val gallery = restTemplate.exchange(
            url("/api/v1/salons/${UUID.randomUUID()}/gallery"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            Array<GalleryImageResponse>::class.java,
        )

        assertEquals(HttpStatus.OK, gallery.statusCode)
        assertTrue(requireNotNull(gallery.body).isEmpty())
    }
}
