package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingForCustomerRequest
import ai.rojan.backend.api.booking.TimeSlotResponse
import ai.rojan.backend.api.publicsalon.PublicSalonResponse
import ai.rojan.backend.api.publicsalon.PublicServiceResponse
import ai.rojan.backend.api.publicsalon.PublicSpecialistResponse
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.ChangeSalonSlugRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonMembershipResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.schedule.SetWeeklyAvailabilityRequest
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
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
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * ROJAN Real Salon Pilot Launch Readiness - RBAC validation. Exercises the
 * `SalonMembership`/`Permission`/`SalonPermissionResolver` model added for
 * the pilot (see `domain/salon/Permission.kt`, `SalonRole.kt`,
 * `SalonMembership.kt`, `application/salon/SalonPermissionResolver.kt`),
 * plus the new public slug-based browsing endpoints - against the real HTTP
 * layer and embedded Postgres, same pattern as every other file in this
 * directory. Each `@Test` builds its own minimal, disposable fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonPilotRbacIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var customerRepository: CustomerRepository

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

    private fun nextDayOfWeek(dayOfWeek: DayOfWeek): LocalDate {
        val base = LocalDate.now().plusDays(14)
        return base.plusDays(((dayOfWeek.value - base.dayOfWeek.value + 7) % 7).toLong())
    }

    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            // 4-8 digits, not a fixed count — matches OtpPolicy.codeLength's
            // valid range (application.yml's own default is 4, not 6).
            .let { Regex("""code is (\d{4,8})""").find(it)!!.groupValues[1] }

    private fun registerAndLogin(fullName: String): Pair<String, UUID> {
        val email = "rbac.${System.nanoTime()}@example.com"
        val registered = restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = fullName, role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken to requireNotNull(registered.body).id
    }

    private fun otpRegisterAndLogin(fullName: String): Triple<String, UUID, String> {
        val phone = "+9891${(1_000_000..9_999_999).random()}"
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val code = lastCodeSentTo(phone)
        val verify = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = phone, code = code, fullName = fullName),
            AuthResponse::class.java,
        )
        val body = requireNotNull(verify.body)
        return Triple(body.accessToken, body.user.id, phone)
    }

    private fun createSalon(ownerToken: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    private fun createCategory(ownerToken: String, salonId: UUID, name: String): ServiceCategoryResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/categories"),
            HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest(name, null), bearer(ownerToken)),
            ServiceCategoryResponse::class.java,
        ).body,
    )

    private fun createService(ownerToken: String, salonId: UUID, categoryId: UUID, name: String): ServiceResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/categories/$categoryId/services"),
            HttpMethod.POST,
            HttpEntity(CreateServiceRequest(name, null, 30, BigDecimal("25.00")), bearer(ownerToken)),
            ServiceResponse::class.java,
        ).body,
    )

    private fun createSpecialist(ownerToken: String, salonId: UUID, displayName: String, userId: UUID? = null): SpecialistResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/specialists"),
            HttpMethod.POST,
            HttpEntity(CreateSpecialistRequest(userId, displayName, null, null, "+989120000002", "Stylist"), bearer(ownerToken)),
            SpecialistResponse::class.java,
        ).body,
    )

    private fun assignMembership(ownerToken: String, salonId: UUID, userId: UUID, role: SalonRole): SalonMembershipResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/members/$userId"),
            HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(role), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        ).body,
    )

    // ---- Slug generation & collision ----

    @Test
    fun `salon creation auto-generates a unique slug, and the owner can change it`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Rojan Beauty Studio")
        assertTrue(salonA.slug.isNotBlank())

        val salonB = createSalon(ownerToken, "Rojan Beauty Studio")
        assertFalse(salonA.slug == salonB.slug, "two salons with the same name must not collide on slug")

        val renamed = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/slug"),
            HttpMethod.PATCH,
            HttpEntity(ChangeSalonSlugRequest("rojan-beauty-official"), bearer(ownerToken)),
            SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, renamed.statusCode)
        assertEquals("rojan-beauty-official", renamed.body!!.slug)

        val collision = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/slug"),
            HttpMethod.PATCH,
            HttpEntity(ChangeSalonSlugRequest("rojan-beauty-official"), bearer(ownerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, collision.statusCode)
        assertTrue(collision.body!!.contains("SALON_SLUG_ALREADY_TAKEN"))
    }

    // ---- Public browsing (customer QR journey) ----

    @Test
    fun `unauthenticated visitor can browse a salon's active catalog by slug, and inactive rows never appear`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val haircut = createService(ownerToken, salon.id, hair.id, "Women's Haircut")
        val toDeactivate = createService(ownerToken, salon.id, hair.id, "Discontinued Service")
        createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        // A salon starts DRAFT and is invisible on the public slug surface until activated
        // (see ROJAN Real Salon Activation Flow / SalonOnboardingStatus) - not covered by this
        // test's own scope, but required setup for the public-browsing assertions below.
        val activation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, activation.statusCode)

        val deactivateResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${hair.id}/services/${toDeactivate.id}"), HttpMethod.DELETE, HttpEntity<Void>(bearer(ownerToken)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, deactivateResponse.statusCode)

        // No Authorization header anywhere below - this is the actual unauthenticated customer path.
        val publicSalon = restTemplate.getForEntity(url("/api/v1/public/salons/${salon.slug}"), PublicSalonResponse::class.java)
        assertEquals(HttpStatus.OK, publicSalon.statusCode)
        assertEquals(salon.id, publicSalon.body!!.id)

        val publicServices = restTemplate.getForEntity(
            url("/api/v1/public/salons/${salon.slug}/categories/${hair.id}/services"), Array<PublicServiceResponse>::class.java,
        ).body!!
        assertTrue(publicServices.any { it.id == haircut.id })
        assertFalse(publicServices.any { it.id == toDeactivate.id }, "a deactivated service must never appear on the public catalog")

        val publicSpecialists = restTemplate.getForEntity(
            url("/api/v1/public/salons/${salon.slug}/specialists"), Array<PublicSpecialistResponse>::class.java,
        ).body!!
        assertTrue(publicSpecialists.any { it.displayName == "Mariam Karimi" })

        val unknownSlug = restTemplate.getForEntity(url("/api/v1/public/salons/does-not-exist"), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, unknownSlug.statusCode)
    }

    // ---- Manager role ----

    @Test
    fun `a manager gets operational access but not salon settings or membership management`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (managerToken, managerUserId) = registerAndLogin("Mariam Manager")
        assignMembership(ownerToken, salon.id, managerUserId, SalonRole.MANAGER)

        // Manager CAN manage the catalog.
        val category = createCategory(managerToken, salon.id, "Hair")
        assertTrue(category.active)

        // Manager CANNOT change salon settings...
        val slugAttempt = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/slug"), HttpMethod.PATCH,
            HttpEntity(ChangeSalonSlugRequest("hijacked"), bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, slugAttempt.statusCode)

        // ...or grant themself (or anyone else) membership - the privilege-escalation chain this design closes.
        val (_, strangerUserId) = registerAndLogin("Random Stranger")
        val escalation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/members/$strangerUserId"), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.MANAGER), bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, escalation.statusCode)
    }

    // ---- Receptionist role ----

    @Test
    fun `a receptionist can create and confirm bookings but cannot touch the catalog`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val service = createService(ownerToken, salon.id, hair.id, "Women's Haircut")
        val specialist = createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        val monday = nextDayOfWeek(DayOfWeek.MONDAY)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )

        val (receptionToken, receptionUserId) = registerAndLogin("Nazanin Reception")
        assignMembership(ownerToken, salon.id, receptionUserId, SalonRole.RECEPTIONIST)

        // Receptionist CANNOT manage the catalog.
        val categoryAttempt = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest("Nails", null), bearer(receptionToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, categoryAttempt.statusCode)

        // Receptionist CAN see and manage bookings for the salon.
        val slot = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=$monday&slotIntervalMinutes=30"),
            HttpMethod.GET, HttpEntity<Void>(bearer(receptionToken)), Array<TimeSlotResponse>::class.java,
        ).body!!.first()

        val (_, customerUserId, phone) = otpRegisterAndLogin("Parisa Customer")
        val customer = customerRepository.save(
            Customer.create(SalonId(salon.id), UserId(customerUserId), "Parisa Customer", PhoneNumber(phone), null, null),
        )

        val booking = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"), HttpMethod.POST,
            HttpEntity(CreateBookingForCustomerRequest(customer.id.value, service.id, specialist.id, slot.start, null), bearer(receptionToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, booking.statusCode)

        val confirmed = restTemplate.exchange(
            url("/api/v1/bookings/${booking.body!!.id}/confirm"), HttpMethod.PATCH,
            HttpEntity<Void>(bearer(receptionToken)), BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, confirmed.statusCode)
        assertEquals(BookingStatus.CONFIRMED, confirmed.body!!.status)
    }

    // ---- Specialist self-service ----

    @Test
    fun `a specialist manages their own schedule and eligibility, never another specialist's`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val service = createService(ownerToken, salon.id, hair.id, "Women's Haircut")

        val (mariamToken, mariamUserId) = registerAndLogin("Mariam Karimi")
        val mariam = createSpecialist(ownerToken, salon.id, "Mariam Karimi", mariamUserId)
        val (_, nazaninUserId) = registerAndLogin("Nazanin Rezaei")
        val nazanin = createSpecialist(ownerToken, salon.id, "Nazanin Rezaei", nazaninUserId)

        // Mariam can set her own weekly availability.
        val ownAvailability = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${mariam.id}/schedule/weekly-availability/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(mariamToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, ownAvailability.statusCode)

        // Mariam cannot set Nazanin's availability.
        val otherAvailability = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${nazanin.id}/schedule/weekly-availability/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(mariamToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, otherAvailability.statusCode)

        // Mariam can toggle her own service eligibility...
        val ownEligibility = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${mariam.id}/services/${service.id}"), HttpMethod.PUT,
            HttpEntity<Void>(bearer(mariamToken)), String::class.java,
        )
        assertEquals(HttpStatus.OK, ownEligibility.statusCode)

        // ...but not Nazanin's.
        val otherEligibility = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${nazanin.id}/services/${service.id}"), HttpMethod.PUT,
            HttpEntity<Void>(bearer(mariamToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, otherEligibility.statusCode)
    }
}
