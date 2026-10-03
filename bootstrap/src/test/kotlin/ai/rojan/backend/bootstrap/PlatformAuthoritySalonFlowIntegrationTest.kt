package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.platformauthority.PlatformSalonResponse
import ai.rojan.backend.api.platformauthority.SuspendPlatformSalonRequest
import ai.rojan.backend.api.platformauthority.UpdatePlatformSalonRequest
import ai.rojan.backend.api.publicsalon.PublicSalonListResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonCompletenessResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.util.UriComponentsBuilder

/**
 * Admin Salon Visibility (the "missing third leg" of the Salon Onboarding/Activation lifecycle):
 * real HTTP + embedded Postgres, same pattern as every other file in this directory. Proves, end to
 * end, that [ai.rojan.backend.api.platformauthority.PlatformAuthoritySalonController]
 * (`GET /api/v1/platform-authority/salons`) is the one new, platform-role-gated way to see a DRAFT
 * salon, while `GET /api/v1/public/salons` (the real Customer/Marketplace contract,
 * [ai.rojan.backend.api.publicsalon.PublicSalonDirectoryController]) and
 * `GET /api/v1/salons` (the real authenticated Manager contract,
 * [ai.rojan.backend.api.salon.SalonController]) are both completely untouched and keep excluding it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PlatformAuthoritySalonFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var userRepository: UserRepository

    private val restTemplate = TestRestTemplate()
    private val logAppender = ListAppender<ILoggingEvent>()
    private val smsLogger = LoggerFactory.getLogger("ai.rojan.backend.infrastructure.sms.LoggingSmsProvider") as Logger

    @BeforeEach
    fun attachLogAppender() {
        logAppender.start()
        smsLogger.addAppender(logAppender)
    }

    @AfterEach
    fun detachLogAppender() {
        smsLogger.detachAppender(logAppender)
        logAppender.stop()
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun randomPhone() = "+9891${(1_000_000..9_999_999).random()}"

    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            .let { Regex("""code is (\d{4,8})""").find(it)!!.groupValues[1] }

    private fun loginViaOtp(phone: String): String {
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val code = lastCodeSentTo(phone)
        val verify = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = phone, code = code, fullName = null),
            AuthResponse::class.java,
        )
        return requireNotNull(verify.body).accessToken
    }

    /** Seeds a real, active PLATFORM_ADMIN directly - same approach [PlatformAuthorityFlowIntegrationTest] already uses, see its own doc comment for why. */
    private fun seedPlatformAdmin(fullName: String): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), fullName, UserRole.PLATFORM_ADMIN))
        return loginViaOtp(phone)
    }

    private fun seedPlatformReviewer(fullName: String): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), fullName, UserRole.PLATFORM_REVIEWER))
        return loginViaOtp(phone)
    }

    private fun registerAndLoginManager(fullName: String): String {
        val email = "platform-salon.${System.nanoTime()}@example.com"
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

    private fun listPlatformSalons(token: String, name: String? = null, page: Int = 0, size: Int = 20): org.springframework.http.ResponseEntity<PagedResponse<SalonResponse>> {
        val uri = UriComponentsBuilder.fromHttpUrl(url("/api/v1/platform-authority/salons"))
            .queryParam("page", page)
            .queryParam("size", size)
            .apply { if (name != null) queryParam("name", name) }
            .build().toUri()
        return restTemplate.exchange(
            uri, HttpMethod.GET, HttpEntity<Void>(bearer(token)),
            object : org.springframework.core.ParameterizedTypeReference<PagedResponse<SalonResponse>>() {},
        )
    }

    /** Same endpoint as [listPlatformSalons], deserialized into the real [PlatformSalonResponse] (with [PlatformSalonResponse.ownerName]) instead of the bare [SalonResponse] every other test here uses - only where a test actually needs to assert on `ownerName`/the richer filters. */
    private fun listPlatformSalonsFull(
        token: String,
        name: String? = null,
        owner: String? = null,
        phone: String? = null,
        status: String? = null,
        verified: Boolean? = null,
        city: String? = null,
        sort: String? = null,
    ): PagedResponse<PlatformSalonResponse> {
        // Builds the query string as a plain string, each value encoded with java.net.URLEncoder
        // (application/x-www-form-urlencoded) - UriComponentsBuilder was tried first and rejected:
        // its RFC-3986 `.encode()` leaves a literal `+` unescaped (legal there per RFC 3986), but
        // the servlet container decodes query strings using the older x-www-form-urlencoded
        // convention where `+` means space, so `.encode()` alone silently turned "+98…" into " 98…"
        // server-side (confirmed by a real failing run and a Hibernate parameter-binding trace);
        // pre-encoding with URLEncoder and then also calling `.build()` without `.encode()` instead
        // double-encoded the value (`%2B` became `%252B`, also confirmed by trace) - `UriComponents`
        // re-escapes an already-encoded `%` unless `.encode()` is told the value is pre-encoded, a
        // distinction awkward enough in this version to be worth avoiding entirely. A plain
        // `java.net.URI.create(...)` of an already-correctly-escaped string has no such ambiguity -
        // every character in the query string is or isn't a legal URI character, full stop, and
        // URLEncoder is the same real encoding convention the Website's own client uses too (via
        // `URLSearchParams`) - this whole class of bug is specific to this test helper, not to any
        // real caller.
        fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        val params = buildList {
            if (name != null) add("name=${enc(name)}")
            if (owner != null) add("owner=${enc(owner)}")
            if (phone != null) add("phone=${enc(phone)}")
            if (status != null) add("status=${enc(status)}")
            if (verified != null) add("verified=$verified")
            if (city != null) add("city=${enc(city)}")
            if (sort != null) add("sort=${enc(sort)}")
        }
        val uri = java.net.URI.create(url("/api/v1/platform-authority/salons") + "?" + params.joinToString("&"))
        return requireNotNull(
            restTemplate.exchange(
                uri, HttpMethod.GET, HttpEntity<Void>(bearer(token)),
                object : org.springframework.core.ParameterizedTypeReference<PagedResponse<PlatformSalonResponse>>() {},
            ).body,
        )
    }

    private fun listPublicSalons(search: String? = null): List<PublicSalonListResponse> {
        val uri = UriComponentsBuilder.fromHttpUrl(url("/api/v1/public/salons"))
            .apply { if (search != null) queryParam("search", search) }
            .build().toUri()
        return requireNotNull(
            restTemplate.exchange(
                uri, HttpMethod.GET, HttpEntity<Void>(HttpHeaders()),
                object : org.springframework.core.ParameterizedTypeReference<PagedResponse<PublicSalonListResponse>>() {},
            ).body,
        ).content
    }

    @Test
    fun `PLATFORM_ADMIN can see a freshly created DRAFT salon via the new endpoint, with its real status`() {
        val adminToken = seedPlatformAdmin("Admin Salon One")
        val ownerToken = registerAndLoginManager("Owner One")
        val draft = createSalon(ownerToken, "Admin-Visible Draft Salon ${System.nanoTime()}")
        assertEquals(SalonOnboardingStatus.DRAFT, draft.onboardingStatus, "a freshly created salon must start DRAFT")

        val response = listPlatformSalons(adminToken, name = draft.name)

        assertEquals(HttpStatus.OK, response.statusCode)
        val found = response.body!!.content.find { it.id == draft.id }
        assertTrue(found != null, "PLATFORM_ADMIN must see the DRAFT salon via GET /api/v1/platform-authority/salons")
        assertEquals(SalonOnboardingStatus.DRAFT, found!!.onboardingStatus)
        assertTrue(found.active)
        assertEquals(draft.ownerId, found.ownerId)
        assertEquals(draft.slug, found.slug)
    }

    @Test
    fun `PLATFORM_REVIEWER can also list DRAFT salons - read access is shared with PLATFORM_ADMIN`() {
        val reviewerToken = seedPlatformReviewer("Reviewer Salon One")
        val ownerToken = registerAndLoginManager("Owner Two")
        val draft = createSalon(ownerToken, "Reviewer-Visible Draft Salon ${System.nanoTime()}")

        val response = listPlatformSalons(reviewerToken, name = draft.name)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.content.any { it.id == draft.id })
    }

    @Test
    fun `a normal authenticated MANAGER cannot access platform-wide salon listing`() {
        val ownerToken = registerAndLoginManager("Owner Three")
        createSalon(ownerToken, "Someone Else's Draft ${System.nanoTime()}")

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `a CUSTOMER cannot access platform-wide salon listing either`() {
        val customerToken = loginViaOtp(randomPhone())

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons"), HttpMethod.GET, HttpEntity<Void>(bearer(customerToken)), String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `an unauthenticated caller cannot access platform-wide salon listing`() {
        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons"), HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `a DRAFT salon never appears via the real public discovery endpoint, even though PLATFORM_ADMIN can see it`() {
        val adminToken = seedPlatformAdmin("Admin Salon Two")
        val ownerToken = registerAndLoginManager("Owner Four")
        val uniqueName = "Still Draft Salon ${System.nanoTime()}"
        val draft = createSalon(ownerToken, uniqueName)

        val viaPlatformAuthority = listPlatformSalons(adminToken, name = uniqueName).body!!.content
        assertTrue(viaPlatformAuthority.any { it.id == draft.id }, "sanity check: the admin endpoint does see it")

        val viaPublicDirectory = listPublicSalons(search = uniqueName)
        assertFalse(viaPublicDirectory.any { it.id == draft.id }, "GET /api/v1/public/salons must never expose a DRAFT salon")
    }

    @Test
    fun `once activated, the real public discovery endpoint picks the salon up automatically - no backend change needed there`() {
        val ownerToken = registerAndLoginManager("Owner Five")
        val uniqueName = "Soon Active Salon ${System.nanoTime()}"
        val salon = createSalon(ownerToken, uniqueName)

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(ai.rojan.backend.api.salon.CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
            ai.rojan.backend.api.salon.ServiceCategoryResponse::class.java,
        ).body!!.let { category ->
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories/${category.id}/services"), HttpMethod.POST,
                HttpEntity(
                    ai.rojan.backend.api.salon.CreateServiceRequest("Haircut", null, 30, java.math.BigDecimal("25.00")),
                    bearer(ownerToken),
                ),
                ai.rojan.backend.api.salon.ServiceResponse::class.java,
            )
        }
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"), HttpMethod.POST,
            HttpEntity(
                ai.rojan.backend.api.salon.CreateSpecialistRequest(null, "Stylist One", null, null, "+989120000099", "Stylist"),
                bearer(ownerToken),
            ),
            ai.rojan.backend.api.salon.SpecialistResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(
                ai.rojan.backend.api.schedule.SetWorkingHoursRequest(
                    listOf(ai.rojan.backend.api.schedule.TimeIntervalDto(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0))),
                ),
                bearer(ownerToken),
            ),
            String::class.java,
        )

        // Still DRAFT, still absent from public discovery, right up until the explicit activate call.
        assertFalse(listPublicSalons(search = uniqueName).any { it.id == salon.id })

        val activation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, activation.statusCode)
        assertEquals(SalonOnboardingStatus.ACTIVE, activation.body!!.onboardingStatus)

        val viaPublicDirectory = listPublicSalons(search = uniqueName)
        assertTrue(viaPublicDirectory.any { it.id == salon.id }, "an ACTIVE salon must be discoverable via the existing public endpoint, unchanged")
    }

    @Test
    fun `pagination and name filter both work on the new platform-wide salon listing`() {
        val adminToken = seedPlatformAdmin("Admin Salon Three")
        val ownerToken = registerAndLoginManager("Owner Six")
        val marker = System.nanoTime()
        val names = listOf("Zeta $marker A", "Zeta $marker B", "Zeta $marker C")
        names.forEach { createSalon(ownerToken, it) }

        val firstPage = listPlatformSalons(adminToken, name = "Zeta $marker", page = 0, size = 2).body!!
        val secondPage = listPlatformSalons(adminToken, name = "Zeta $marker", page = 1, size = 2).body!!

        assertEquals(3L, firstPage.totalElements)
        assertEquals(2, firstPage.content.size)
        assertEquals(1, secondPage.content.size)
        val allNames = (firstPage.content + secondPage.content).map { it.name }
        assertEquals(names.sorted(), allNames.sorted())
    }

    @Test
    fun `the full filter set - owner, phone, status, verified, city - and sort all work against a real Postgres, and ownerName is resolved`() {
        val adminToken = seedPlatformAdmin("Admin Salon Four")
        val marker = System.nanoTime()
        val ownerToken = registerAndLoginManager("Filterable Owner $marker")
        val salon = createSalon(ownerToken, "Filter Target Salon $marker")
        // A clean, unambiguous E.164-style phone (no spaces) - the `createSalon` helper's own
        // default "+1 555 0100" is pre-existing fixture data shared with other tests, deliberately
        // left unchanged here; this filter test needs its own, cleanly-formatted value instead of
        // reusing it.
        val cleanPhone = randomPhone()
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.PUT,
            HttpEntity(
                ai.rojan.backend.api.salon.UpdateSalonRequest(salon.name, null, cleanPhone, null, salon.address, city = "Shiraz $marker"),
                bearer(ownerToken),
            ),
            SalonResponse::class.java,
        )

        val byOwner = listPlatformSalonsFull(adminToken, owner = "Filterable Owner $marker")
        assertTrue(byOwner.content.any { it.id == salon.id })
        assertEquals("Filterable Owner $marker", byOwner.content.first { it.id == salon.id }.ownerName, "ownerName must be the real owner's full name")

        val byPhone = listPlatformSalonsFull(adminToken, phone = cleanPhone)
        assertTrue(byPhone.content.any { it.id == salon.id })

        val byStatusDraft = listPlatformSalonsFull(adminToken, name = salon.name, status = "DRAFT")
        assertTrue(byStatusDraft.content.any { it.id == salon.id }, "a freshly created salon is DRAFT")
        val byStatusPublished = listPlatformSalonsFull(adminToken, name = salon.name, status = "PUBLISHED")
        assertFalse(byStatusPublished.content.any { it.id == salon.id })

        val byVerifiedFalse = listPlatformSalonsFull(adminToken, name = salon.name, verified = false)
        assertTrue(byVerifiedFalse.content.any { it.id == salon.id }, "a freshly created salon is not ROJAN-verified")
        val byVerifiedTrue = listPlatformSalonsFull(adminToken, name = salon.name, verified = true)
        assertFalse(byVerifiedTrue.content.any { it.id == salon.id })

        val byCity = listPlatformSalonsFull(adminToken, city = "Shiraz $marker")
        assertTrue(byCity.content.any { it.id == salon.id })

        val sortedByNameAsc = listPlatformSalonsFull(adminToken, name = salon.name, sort = "name,asc")
        assertTrue(sortedByNameAsc.content.any { it.id == salon.id })
    }

    @Test
    fun `PLATFORM_ADMIN can suspend a salon with a reason, it disappears from public discovery, and PLATFORM_ADMIN can reinstate it`() {
        val adminToken = seedPlatformAdmin("Admin Suspend One")
        val ownerToken = registerAndLoginManager("Owner Suspend One")
        val marker = System.nanoTime()
        val salon = createSalon(ownerToken, "Suspend Target $marker")
        // Activate it first so it is genuinely publicly discoverable before the suspend.
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(ai.rojan.backend.api.salon.CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
            ai.rojan.backend.api.salon.ServiceCategoryResponse::class.java,
        ).body!!.let { category ->
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories/${category.id}/services"), HttpMethod.POST,
                HttpEntity(
                    ai.rojan.backend.api.salon.CreateServiceRequest("Haircut", null, 30, java.math.BigDecimal("25.00")),
                    bearer(ownerToken),
                ),
                ai.rojan.backend.api.salon.ServiceResponse::class.java,
            )
        }
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"), HttpMethod.POST,
            HttpEntity(
                ai.rojan.backend.api.salon.CreateSpecialistRequest(null, "Stylist Suspend", null, null, "+989120000098", "Stylist"),
                bearer(ownerToken),
            ),
            ai.rojan.backend.api.salon.SpecialistResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(
                ai.rojan.backend.api.schedule.SetWorkingHoursRequest(
                    listOf(ai.rojan.backend.api.schedule.TimeIntervalDto(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0))),
                ),
                bearer(ownerToken),
            ),
            String::class.java,
        )
        restTemplate.exchange(url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)
        assertTrue(listPublicSalons(search = salon.name).any { it.id == salon.id }, "sanity check: it is publicly discoverable before suspend")

        val suspend = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/suspend"), HttpMethod.POST,
            HttpEntity(SuspendPlatformSalonRequest("تکراری است"), bearer(adminToken)),
            PlatformSalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, suspend.statusCode)
        assertFalse(suspend.body!!.active)

        assertFalse(listPublicSalons(search = salon.name).any { it.id == salon.id }, "a suspended salon must disappear from public discovery")

        val reinstate = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/reinstate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), PlatformSalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, reinstate.statusCode)
        assertTrue(reinstate.body!!.active)
        assertTrue(listPublicSalons(search = salon.name).any { it.id == salon.id }, "a reinstated, already-activated salon must reappear in public discovery")
    }

    @Test
    fun `suspend requires a non-blank reason and is PLATFORM_ADMIN only - PLATFORM_REVIEWER gets 403`() {
        val adminToken = seedPlatformAdmin("Admin Suspend Two")
        val reviewerToken = seedPlatformReviewer("Reviewer Suspend Two")
        val ownerToken = registerAndLoginManager("Owner Suspend Two")
        val salon = createSalon(ownerToken, "Suspend Validation Target ${System.nanoTime()}")

        val blankReason = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/suspend"), HttpMethod.POST,
            HttpEntity(SuspendPlatformSalonRequest("   "), bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, blankReason.statusCode)

        val reviewerAttempt = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/suspend"), HttpMethod.POST,
            HttpEntity(SuspendPlatformSalonRequest("a reason"), bearer(reviewerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, reviewerAttempt.statusCode)
    }

    @Test
    fun `PLATFORM_ADMIN can edit a salon's identity, location and business-profile fields in one call`() {
        val adminToken = seedPlatformAdmin("Admin Edit One")
        val ownerToken = registerAndLoginManager("Owner Edit One")
        val salon = createSalon(ownerToken, "Edit Target ${System.nanoTime()}")

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}"), HttpMethod.PUT,
            HttpEntity(
                UpdatePlatformSalonRequest(
                    name = "Edited By Admin",
                    description = "Edited description",
                    phone = "+15559990000",
                    email = "edited@example.com",
                    address = "Edited address",
                    latitude = 35.7,
                    longitude = 51.3,
                    city = "Tehran",
                    activityStartJalaliYear = 1395,
                    hasInternalExtensions = true,
                    sellsProducts = true,
                    hasCafe = false,
                    hasStaffUniform = null,
                    isNeighborhoodSalon = null,
                    isCityCenterSalon = null,
                ),
                bearer(adminToken),
            ),
            PlatformSalonResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("Edited By Admin", response.body!!.name)
        assertEquals("Tehran", response.body!!.city)

        val getAfter = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.GET, HttpEntity<Void>(bearer(adminToken)), SalonResponse::class.java,
        )
        assertEquals("Edited By Admin", getAfter.body!!.name, "the edit must really be persisted")
    }

    @Test
    fun `PLATFORM_ADMIN can clear an invalid logo without deleting the salon or the cover`() {
        val adminToken = seedPlatformAdmin("Admin Media One")
        val ownerToken = registerAndLoginManager("Owner Media One")
        val salon = createSalon(ownerToken, "Media Target ${System.nanoTime()}")

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/identity-media/LOGO"), HttpMethod.DELETE,
            HttpEntity<Void>(bearer(adminToken)), PlatformSalonResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(null, response.body!!.logoMediaId)
        assertEquals(salon.id, response.body!!.id, "the salon itself must still exist, only its logo slot is cleared")
    }

    @Test
    fun `PLATFORM_ADMIN can read a salon's completeness even though the owner-scoped route would 403 them`() {
        val adminToken = seedPlatformAdmin("Admin Completeness One")
        val ownerToken = registerAndLoginManager("Owner Completeness One")
        val salon = createSalon(ownerToken, "Completeness Target ${System.nanoTime()}")

        val ownerScopedAttempt = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.GET, HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, ownerScopedAttempt.statusCode, "a platform admin is not a member of this salon")

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/completeness"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), SalonCompletenessResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(salon.id, response.body!!.salonId)
        assertEquals(3, response.body!!.missingForActivation.size, "a brand-new salon is missing all three activation requirements")
    }
}
