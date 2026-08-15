package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingForCustomerRequest
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.booking.TimeSlotResponse
import ai.rojan.backend.api.publicsalon.PublicSalonResponse
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.salon.UpdateSalonRequest
import ai.rojan.backend.api.schedule.SetWeeklyAvailabilityRequest
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * ROJAN Real Salon Activation Flow - exercises the DRAFT -> ACTIVE lifecycle
 * (`Salon.onboardingStatus`), the printable QR endpoint, and the self-service
 * booking -> CRM `Customer` auto-association, against the real HTTP layer
 * and embedded Postgres, same pattern as every other file in this directory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonActivationFlowIntegrationTest {

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
        val email = "activation.${System.nanoTime()}@example.com"
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

    private fun createSpecialist(ownerToken: String, salonId: UUID, displayName: String): SpecialistResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/specialists"),
            HttpMethod.POST,
            HttpEntity(CreateSpecialistRequest(null, displayName, null, null), bearer(ownerToken)),
            SpecialistResponse::class.java,
        ).body,
    )

    private fun setMondayWorkingHours(ownerToken: String, salonId: UUID) {
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            String::class.java,
        )
    }

    @Test
    fun `a draft salon is invisible on the public slug endpoint until activated, then visible with its full profile`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        assertEquals(SalonOnboardingStatus.DRAFT, salon.onboardingStatus)

        val draftLookup = restTemplate.getForEntity(url("/api/v1/public/salons/${salon.slug}"), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, draftLookup.statusCode)

        // Activation before setup is complete is rejected, listing exactly what's missing.
        val prematureActivation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, prematureActivation.statusCode)
        assertTrue(prematureActivation.body!!.contains("SALON_NOT_READY_FOR_ACTIVATION"))
        assertTrue(prematureActivation.body!!.contains("active service"))
        assertTrue(prematureActivation.body!!.contains("active specialist"))
        assertTrue(prematureActivation.body!!.contains("working-hours"))

        // Complete the profile and the three readiness requirements.
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.PUT,
            HttpEntity(
                UpdateSalonRequest(
                    name = salon.name, description = salon.description, phone = salon.phone, email = salon.email,
                    address = salon.address, logoUrl = "https://cdn.rojan.ai/logos/rbs.png", latitude = 35.6892, longitude = 51.3890,
                ),
                bearer(ownerToken),
            ),
            SalonResponse::class.java,
        )
        val hair = createCategory(ownerToken, salon.id, "Hair")
        createService(ownerToken, salon.id, hair.id, "Women's Haircut")
        createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        setMondayWorkingHours(ownerToken, salon.id)

        val activation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, activation.statusCode)
        assertEquals(SalonOnboardingStatus.ACTIVE, activation.body!!.onboardingStatus)

        val publicLookup = restTemplate.getForEntity(url("/api/v1/public/salons/${salon.slug}"), PublicSalonResponse::class.java)
        assertEquals(HttpStatus.OK, publicLookup.statusCode)
        assertEquals("https://cdn.rojan.ai/logos/rbs.png", publicLookup.body!!.logoUrl)
        assertEquals(35.6892, publicLookup.body!!.latitude)
        assertEquals(51.3890, publicLookup.body!!.longitude)
    }

    @Test
    fun `owner can download a printable QR code PNG once the salon exists`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val qr = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/qr-code"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), ByteArray::class.java,
        )

        assertEquals(HttpStatus.OK, qr.statusCode)
        assertEquals(MediaType.IMAGE_PNG, qr.headers.contentType)
        assertNotNull(qr.body)
        assertTrue(qr.body!!.isNotEmpty())
    }

    @Test
    fun `a brand-new customer completing the QR journey gets exactly one CRM customer record, not one per booking`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val service = createService(ownerToken, salon.id, hair.id, "Women's Haircut")
        val specialist = createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        setMondayWorkingHours(ownerToken, salon.id)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        // The public browsing surface (including available-slots) 404s until the salon is activated.
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )
        val monday = nextDayOfWeek(DayOfWeek.MONDAY)

        val (customerToken, customerUserId, _) = otpRegisterAndLogin("Parisa Customer")
        assertNull(customerRepository.findBySalonIdAndUserId(SalonId(salon.id), UserId(customerUserId)))

        val firstSlot = restTemplate.getForEntity(
            url("/api/v1/public/salons/${salon.slug}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=$monday&slotIntervalMinutes=30"),
            Array<TimeSlotResponse>::class.java,
        ).body!!.first()

        val firstBooking = restTemplate.exchange(
            url("/api/v1/bookings"), HttpMethod.POST,
            HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, firstSlot.start, null), bearer(customerToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, firstBooking.statusCode)

        val afterFirst = customerRepository.findBySalonIdAndUserId(SalonId(salon.id), UserId(customerUserId))
        assertNotNull(afterFirst, "self-service booking must create a linked CRM customer")
        assertEquals("Parisa Customer", afterFirst!!.fullName)

        val secondSlot = restTemplate.getForEntity(
            url("/api/v1/public/salons/${salon.slug}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=$monday&slotIntervalMinutes=30"),
            Array<TimeSlotResponse>::class.java,
        ).body!!.first { it.start != firstSlot.start }

        restTemplate.exchange(
            url("/api/v1/bookings"), HttpMethod.POST,
            HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, secondSlot.start, null), bearer(customerToken)),
            BookingResponse::class.java,
        )

        val afterSecond = customerRepository.findBySalonIdAndUserId(SalonId(salon.id), UserId(customerUserId))
        assertEquals(afterFirst.id, afterSecond!!.id, "a repeat booking must not create a second customer row")
    }

    private data class BookableFixture(val salon: SalonResponse, val service: ServiceResponse, val specialist: SpecialistResponse)

    /** Fully setup for activation (readiness requirements all met) but deliberately never activated - isolates the activation guard itself from any other 404/validation the fixture could otherwise trigger. */
    private fun setUpDraftButBookableSalon(ownerToken: String, name: String): BookableFixture {
        val salon = createSalon(ownerToken, name)
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val service = createService(ownerToken, salon.id, hair.id, "Women's Haircut")
        val specialist = createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        setMondayWorkingHours(ownerToken, salon.id)
        return BookableFixture(salon, service, specialist)
    }

    @Test
    fun `self-service booking against a DRAFT salon is rejected even when the caller already knows its id, and creates no CRM customer`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val (salon, service, specialist) = setUpDraftButBookableSalon(ownerToken, "Still Onboarding Salon")
        assertEquals(SalonOnboardingStatus.DRAFT, salon.onboardingStatus)

        // Simulates a caller who already has the salon's raw UUID (never obtainable via the
        // public/QR surface, which already 404s for DRAFT - see the test above) rather than
        // discovering it through browsing - the residual gap flagged in the Phase 6 report §1.4.
        val (customerToken, customerUserId, _) = otpRegisterAndLogin("Parisa Customer")
        val response = restTemplate.exchange(
            url("/api/v1/bookings"), HttpMethod.POST,
            HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, LocalDate.now().plusDays(21).atTime(10, 0), null), bearer(customerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertTrue(response.body!!.contains("SALON_NOT_ACTIVE"))
        assertNull(
            customerRepository.findBySalonIdAndUserId(SalonId(salon.id), UserId(customerUserId)),
            "a rejected booking against a DRAFT salon must not leave behind a CRM customer record",
        )
    }

    @Test
    fun `reception booking creation against a DRAFT salon is rejected independently of customer association`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val (salon, service, specialist) = setUpDraftButBookableSalon(ownerToken, "Still Onboarding Salon Two")
        assertEquals(SalonOnboardingStatus.DRAFT, salon.onboardingStatus)

        // The reception/owner-authored path never calls EnsureCustomerAssociationUseCase (the
        // customer is already linked) - exercising it proves CreateBookingUseCase's own
        // `salon.requireActivated()` call rejects DRAFT salons on its own, not only as a side
        // effect of the self-service path's customer-association check running first.
        val (_, linkedUserId) = registerAndLogin("Linked Walk-in")
        val linkedCustomer = customerRepository.save(
            Customer.create(SalonId(salon.id), UserId(linkedUserId), "Linked Walk-in", PhoneNumber("+989100000099"), null, null),
        )

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"), HttpMethod.POST,
            HttpEntity(
                CreateBookingForCustomerRequest(linkedCustomer.id.value, service.id, specialist.id, LocalDate.now().plusDays(22).atTime(10, 0), null),
                bearer(ownerToken),
            ),
            String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertTrue(response.body!!.contains("SALON_NOT_ACTIVE"))
    }

    @Test
    fun `an activated salon accepts self-service booking and customer association normally`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val (salon, service, specialist) = setUpDraftButBookableSalon(ownerToken, "Ready For Business Salon")
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        ).also { assertEquals(HttpStatus.OK, it.statusCode) }

        val (customerToken, customerUserId, _) = otpRegisterAndLogin("Parisa Customer")
        val response = restTemplate.exchange(
            url("/api/v1/bookings"), HttpMethod.POST,
            HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, LocalDate.now().plusDays(23).atTime(10, 0), null), bearer(customerToken)),
            BookingResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(customerRepository.findBySalonIdAndUserId(SalonId(salon.id), UserId(customerUserId)), "an activated salon must still auto-create the CRM customer as before")
    }

    @Test
    fun `assigning a membership role to the salon's own owner is rejected`() {
        val (ownerToken, ownerUserId) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/members/$ownerUserId"), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.MANAGER), bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertTrue(response.body!!.contains("INVALID_MEMBERSHIP_ASSIGNMENT"))
    }
}
