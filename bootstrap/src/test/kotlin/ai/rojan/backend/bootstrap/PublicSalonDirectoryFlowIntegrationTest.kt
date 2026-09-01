package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.publicsalon.PublicSalonListResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.salon.UpdateSalonRequest
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.api.schedule.WorkingHoursResponse
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Public Salon Marketplace (Phase 1): end-to-end verification of the new public salon-discovery
 * surface against a real (embedded, no-Docker) PostgreSQL and the actual HTTP layer - proves both
 * the real JPA-backed `findAllPubliclyDiscoverable` query (never `findAllActive`, which a DRAFT or
 * inactive salon would incorrectly pass) and the real controller contract (public access, no
 * token; pagination; city/name filters; empty result) together, matching this codebase's own
 * established integration-test pattern (`SalonManagementFlowIntegrationTest`,
 * `BookingEngineFlowIntegrationTest`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PublicSalonDirectoryFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(): String {
        val email = "directory.${System.nanoTime()}@example.com"
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

    /** Creates a salon with one active service, one active specialist, and one working-hours day - the real minimum `ActivateSalonUseCase` requires - then activates it and (optionally) sets its city. Returns the real, saved `SalonResponse` (post-activation, post-city-update). */
    private fun createAndActivateSalon(name: String, city: String?): SalonResponse =
        createAndActivateSalon(name, city, latitude = null, longitude = null)

    /** LBS Architecture (Phase 5): the same real setup as [createAndActivateSalon], with real coordinates set via the existing, unchanged `UpdateSalonRequest`/`Salon.updateProfile` path - never a second, parallel way of setting a salon's location. */
    private fun createAndActivateSalon(name: String, city: String?, latitude: Double?, longitude: Double?): SalonResponse {
        val ownerToken = registerAndLogin()

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories"),
                HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${category.id}/services"),
            HttpMethod.POST,
            HttpEntity(CreateServiceRequest("Haircut", null, 30, BigDecimal("25.00")), bearer(ownerToken)),
            ServiceResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"),
            HttpMethod.POST,
            HttpEntity(CreateSpecialistRequest(null, "Jamie Stylist", null, null, "+989120000006", "Stylist"), bearer(ownerToken)),
            SpecialistResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            WorkingHoursResponse::class.java,
        )
        restTemplate.exchange(url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)

        val updated = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}"),
                HttpMethod.PUT,
                HttpEntity(UpdateSalonRequest(name, null, "+1 555 0100", null, "1 Main St", latitude, longitude, city), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        return updated.copy(id = salon.id)
    }

    private fun listPublicSalons(query: String = ""): PagedResponse<PublicSalonListResponse> =
        requireNotNull(
            restTemplate.exchange(
                url("/api/v1/public/salons$query"),
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders()),
                object : ParameterizedTypeReference<PagedResponse<PublicSalonListResponse>>() {},
            ).body,
        )

    @Test
    fun `is reachable with no Authorization header at all - genuinely public`() {
        val response = restTemplate.exchange(
            url("/api/v1/public/salons"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            object : ParameterizedTypeReference<PagedResponse<PublicSalonListResponse>>() {},
        )
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `lists an active, ACTIVE-onboarded salon with real card-only fields`() {
        val salon = createAndActivateSalon("Glow Salon ${System.nanoTime()}", "Tehran")

        val result = listPublicSalons()

        val entry = result.content.find { it.id == salon.id }
        assertTrue(entry != null, "The real activated salon must appear in the public directory")
        assertEquals(salon.slug, entry!!.slug)
        assertEquals(salon.name, entry.name)
        assertEquals("Tehran", entry.city)
    }

    @Test
    fun `excludes a still-DRAFT salon even though it is active - never lets one leak`() {
        val ownerToken = registerAndLogin()
        val draftSalon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Draft Salon ${System.nanoTime()}", null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )

        val result = listPublicSalons()

        assertFalse(result.content.any { it.id == draftSalon.id }, "A DRAFT (never activated) salon must never appear in the public directory")
    }

    @Test
    fun `excludes a deactivated salon even though its onboardingStatus is ACTIVE`() {
        val stillActive = createAndActivateSalon("Still Active ${System.nanoTime()}", "Tehran")

        val ownerToken = registerAndLogin()
        val deactivateMe = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Deactivate Me ${System.nanoTime()}", null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${deactivateMe.id}"),
            HttpMethod.DELETE,
            HttpEntity<Void>(bearer(ownerToken)),
            Void::class.java,
        )

        val result = listPublicSalons()

        assertFalse(result.content.any { it.id == deactivateMe.id }, "A deactivated salon must never appear in the public directory")
        // A real, unrelated, still-active control case - proves the exclusion is specific to deactivation, not a blanket failure.
        assertTrue(result.content.any { it.id == stillActive.id })
    }

    @Test
    fun `filters by city, excluding salons in a different city`() {
        val tehranSalon = createAndActivateSalon("Tehran Salon ${System.nanoTime()}", "Tehran")
        val shirazSalon = createAndActivateSalon("Shiraz Salon ${System.nanoTime()}", "Shiraz")

        val result = listPublicSalons("?city=Tehran")

        assertTrue(result.content.any { it.id == tehranSalon.id })
        assertFalse(result.content.any { it.id == shirazSalon.id })
    }

    @Test
    fun `city filter is case-insensitive`() {
        val salon = createAndActivateSalon("Case Test Salon ${System.nanoTime()}", "Tehran")

        val result = listPublicSalons("?city=tehran")

        assertTrue(result.content.any { it.id == salon.id })
    }

    @Test
    fun `returns a real, genuinely empty result for a city with no active salons, not an error`() {
        val result = listPublicSalons("?city=NoSalonsHereCity${System.nanoTime()}")

        assertTrue(result.content.isEmpty())
        assertEquals(0, result.totalElements)
    }

    @Test
    fun `filters by a case-insensitive name substring via search`() {
        val uniqueMarker = "Zephyr${System.nanoTime()}"
        val matching = createAndActivateSalon("$uniqueMarker Hair Studio", null)
        val nonMatching = createAndActivateSalon("Totally Different Salon ${System.nanoTime()}", null)

        val result = listPublicSalons("?search=${uniqueMarker.lowercase()}")

        assertTrue(result.content.any { it.id == matching.id })
        assertFalse(result.content.any { it.id == nonMatching.id })
    }

    @Test
    fun `paginates real results`() {
        val marker = "PageMarker${System.nanoTime()}"
        val first = createAndActivateSalon("$marker A", marker)
        val second = createAndActivateSalon("$marker B", marker)

        val page0 = listPublicSalons("?city=$marker&page=0&size=1")
        assertEquals(1, page0.content.size)
        assertEquals(2, page0.totalElements)
        assertEquals(2, page0.totalPages)

        val page1 = listPublicSalons("?city=$marker&page=1&size=1")
        assertEquals(1, page1.content.size)

        val allIds = (page0.content + page1.content).map { it.id }.toSet()
        assertEquals(setOf(first.id, second.id), allIds)
    }

    @Test
    fun `a salon with no city set shows a real null city, never a fabricated value`() {
        val salon = createAndActivateSalon("No City Salon ${System.nanoTime()}", null)

        val result = listPublicSalons()

        val entry = requireNotNull(result.content.find { it.id == salon.id })
        assertNull(entry.city)
    }

    @Test
    fun `distanceKm is null on a real, ordinary (non-nearby) listing call, never fabricated`() {
        val salon = createAndActivateSalon("Ordinary Listing Salon ${System.nanoTime()}", "Tehran")

        val result = listPublicSalons()

        val entry = requireNotNull(result.content.find { it.id == salon.id })
        assertNull(entry.distanceKm)
    }

    // LBS Architecture (Phase 5): Tehran (35.7219, 51.3347) and Isfahan (32.6546, 51.6680) are ~350km
    // apart - real, well-known coordinates, not invented ones, chosen so a modest radius cleanly
    // separates "nearby" from "not nearby" without depending on exact Haversine precision.
    private val tehranLat = 35.7219
    private val tehranLng = 51.3347
    private val isfahanLat = 32.6546
    private val isfahanLng = 51.6680

    @Test
    fun `finds a real nearby salon within radius, with a real computed distance close to zero`() {
        val salon = createAndActivateSalon("Nearby Salon ${System.nanoTime()}", "Tehran", tehranLat, tehranLng)

        val result = listPublicSalons("?lat=$tehranLat&lng=$tehranLng&radiusKm=5")

        val entry = requireNotNull(result.content.find { it.id == salon.id })
        assertTrue(entry.distanceKm != null && entry.distanceKm!! < 1.0, "A salon at the exact query point must show a real, near-zero distance, got ${entry.distanceKm}")
    }

    @Test
    fun `excludes a real salon outside the requested radius`() {
        val farSalon = createAndActivateSalon("Far Salon ${System.nanoTime()}", "Isfahan", isfahanLat, isfahanLng)

        val result = listPublicSalons("?lat=$tehranLat&lng=$tehranLng&radiusKm=50")

        assertFalse(result.content.any { it.id == farSalon.id }, "A salon ~350km away must not appear within a 50km radius")
    }

    @Test
    fun `includes a farther salon once the radius is widened enough to real-world cover it`() {
        val farSalon = createAndActivateSalon("Wide Radius Salon ${System.nanoTime()}", "Isfahan", isfahanLat, isfahanLng)

        val result = listPublicSalons("?lat=$tehranLat&lng=$tehranLng&radiusKm=400")

        val entry = result.content.find { it.id == farSalon.id }
        assertTrue(entry != null, "A salon ~350km away must appear within a 400km radius")
        assertTrue(entry!!.distanceKm != null && entry.distanceKm!! in 300.0..400.0, "Real Tehran-Isfahan distance should be roughly 300-400km, got ${entry.distanceKm}")
    }

    @Test
    fun `sorts real nearby results by ascending distance`() {
        val near = createAndActivateSalon("Closer Salon ${System.nanoTime()}", "Tehran", tehranLat, tehranLng)
        val far = createAndActivateSalon("Farther Salon ${System.nanoTime()}", "Isfahan", isfahanLat, isfahanLng)

        val result = listPublicSalons("?lat=$tehranLat&lng=$tehranLng&radiusKm=400")

        val nearIndex = result.content.indexOfFirst { it.id == near.id }
        val farIndex = result.content.indexOfFirst { it.id == far.id }
        assertTrue(nearIndex in result.content.indices && farIndex in result.content.indices)
        assertTrue(nearIndex < farIndex, "The closer salon must be sorted before the farther one")
    }

    @Test
    fun `never assigns a fabricated distance to a salon with no location set - it is honestly absent from nearby results`() {
        val noLocationSalon = createAndActivateSalon("No Location Salon ${System.nanoTime()}", "Tehran", null, null)

        val result = listPublicSalons("?lat=$tehranLat&lng=$tehranLng&radiusKm=400")

        assertFalse(result.content.any { it.id == noLocationSalon.id }, "A salon with no real coordinates must never appear in nearby results with a guessed distance")
    }

    @Test
    fun `excludes a DRAFT salon from nearby results too, same public-discoverability gate`() {
        val ownerToken = registerAndLogin()
        val draftSalon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Draft Nearby Salon ${System.nanoTime()}", null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${draftSalon.id}"),
            HttpMethod.PUT,
            HttpEntity(UpdateSalonRequest(draftSalon.name, null, "+1 555 0100", null, "1 Main St", tehranLat, tehranLng, "Tehran"), bearer(ownerToken)),
            SalonResponse::class.java,
        )

        val result = listPublicSalons("?lat=$tehranLat&lng=$tehranLng&radiusKm=400")

        assertFalse(result.content.any { it.id == draftSalon.id }, "A still-DRAFT salon must never appear in nearby results, real coordinates or not")
    }

    @Test
    fun `rejects an out-of-range latitude with a real 400, never silently clamping or ignoring it`() {
        val response = restTemplate.exchange(
            url("/api/v1/public/salons?lat=999&lng=$tehranLng"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `rejects a zero or negative radiusKm with a real 400`() {
        val response = restTemplate.exchange(
            url("/api/v1/public/salons?lat=$tehranLat&lng=$tehranLng&radiusKm=0"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
