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
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.customer.CreateCustomerRequest
import ai.rojan.backend.api.customer.CustomerResponse
import ai.rojan.backend.api.customer.CustomerTimelineEntryResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
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
import org.springframework.core.ParameterizedTypeReference
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
 * ROJAN Production Readiness Validation Suite. Automated QA/validation
 * infrastructure only - not a demo product, not a simulation shipped
 * anywhere, and never connected to a real database. Runs against the same
 * disposable, embedded-for-the-duration-of-this-test-run Postgres every
 * other file in this directory uses ([AutoConfigureEmbeddedDatabase]), driven
 * entirely through real HTTP calls to the real controllers - proving the
 * salon-onboarding flow, authentication, authorization, booking lifecycle,
 * the new specialist-service eligibility feature, customer history, and
 * tenant isolation all work correctly before any real salon is onboarded.
 *
 * Each fixture (the "Rojan Beauty Studio" salon and its staff/services) is
 * built fresh per @Test via real UseCases/HTTP endpoints - deliberately
 * minimal, disposable, never written to any database outside this single
 * test process's lifetime.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class ProductionReadinessValidationIntegrationTest {

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

    private fun testPhoneNumber() = "+9891${(1_000_000..9_999_999).random()}"

    // 4-8 digits, not a fixed count — matches OtpPolicy.codeLength's valid
    // range (application.yml's own default is 4, not 6) and the same
    // pattern OtpTestFixtures.kt already uses elsewhere.
    private val CODE_REGEX = Regex("""code is (\d{4,8})""")

    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            .let { CODE_REGEX.find(it)!!.groupValues[1] }

    /** Same 14-days-out technique as [BookingEngineFlowIntegrationTest] - far enough that "earliestStart" trimming never interferes. */
    private fun nextDayOfWeek(dayOfWeek: DayOfWeek): LocalDate {
        val base = LocalDate.now().plusDays(14)
        return base.plusDays(((dayOfWeek.value - base.dayOfWeek.value + 7) % 7).toLong())
    }

    private fun registerAndLogin(role: UserRole, fullName: String): Pair<String, UUID> {
        val email = "prv.${System.nanoTime()}@example.com"
        val registered = restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = fullName, role = role),
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
        val phone = testPhoneNumber()
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
            HttpEntity(CreateSalonRequest(name, "Luxury hair, nails, and makeup studio", "+1 555 0100", null, "128 Blossom Ave"), bearer(ownerToken)),
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

    private fun createService(ownerToken: String, salonId: UUID, categoryId: UUID, name: String, durationMinutes: Int, price: BigDecimal): ServiceResponse =
        requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/categories/$categoryId/services"),
                HttpMethod.POST,
                HttpEntity(CreateServiceRequest(name, null, durationMinutes, price), bearer(ownerToken)),
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

    private fun setAvailability(ownerToken: String, salonId: UUID, specialistId: UUID, dayOfWeek: DayOfWeek) {
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/working-hours/$dayOfWeek"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(19, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/specialists/$specialistId/schedule/weekly-availability/$dayOfWeek"),
            HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(19, 0)))), bearer(ownerToken)),
            String::class.java,
        )
    }

    private fun firstAvailableSlot(callerToken: String, salonId: UUID, specialistId: UUID, serviceId: UUID, date: LocalDate) =
        requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/specialists/$specialistId/available-slots?serviceId=$serviceId&date=$date&slotIntervalMinutes=30"),
                HttpMethod.GET,
                HttpEntity<Void>(bearer(callerToken)),
                Array<TimeSlotResponse>::class.java,
            ).body,
        ).first()

    private fun activateSalon(ownerToken: String, salonId: UUID) {
        restTemplate.exchange(url("/api/v1/salons/$salonId/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)
    }

    private fun assignService(ownerToken: String, salonId: UUID, specialistId: UUID, serviceId: UUID) {
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/specialists/$specialistId/services/$serviceId"),
            HttpMethod.PUT,
            HttpEntity<Void>(bearer(ownerToken)),
            String::class.java,
        )
    }

    // ---- 1. Real tenant creation flow ----

    @Test
    fun `real tenant creation flow works end-to-end through real APIs`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER, "Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        assertTrue(salon.active)

        val hair = createCategory(ownerToken, salon.id, "Hair")
        val haircut = createService(ownerToken, salon.id, hair.id, "Women's Haircut", 45, BigDecimal("45.00"))
        assertTrue(haircut.active)

        val specialist = createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        assertEquals(salon.id, specialist.salonId)

        val monday = nextDayOfWeek(DayOfWeek.MONDAY)
        setAvailability(ownerToken, salon.id, specialist.id, DayOfWeek.MONDAY)
        val slots = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/available-slots?serviceId=${haircut.id}&date=$monday&slotIntervalMinutes=30"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            Array<TimeSlotResponse>::class.java,
        ).body
        assertTrue(slots!!.isNotEmpty(), "expected computed availability once working hours + weekly availability are set")
    }

    // ---- 2. Authentication ----

    @Test
    fun `both authentication paths work - email-password for staff, phone-OTP for customers`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER, "Sara Ahmadi")
        assertTrue(ownerToken.isNotBlank())

        val (customerToken, customerUserId, phone) = otpRegisterAndLogin("Mina First-Timer")
        assertTrue(customerToken.isNotBlank())

        val me = restTemplate.exchange(url("/api/v1/users/me"), HttpMethod.GET, HttpEntity<Void>(bearer(customerToken)), UserResponse::class.java).body
        assertEquals(customerUserId, me!!.id)
        assertEquals(phone, me.phoneNumber)
    }

    // ---- 3. Authorization ----

    @Test
    fun `a non-owner caller is rejected from every owner-only endpoint - authorization is ownerId-based, not role-based`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER, "Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val service = createService(ownerToken, salon.id, hair.id, "Women's Haircut", 45, BigDecimal("45.00"))
        val specialist = createSpecialist(ownerToken, salon.id, "Mariam Karimi")

        val (customerToken, _) = registerAndLogin(UserRole.CUSTOMER, "Random Customer")
        val (strangerOwnerToken, _) = registerAndLogin(UserRole.MANAGER, "Owner Of Nothing")

        for (callerToken in listOf(customerToken, strangerOwnerToken)) {
            val workingHoursResponse = restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/working-hours/MONDAY"),
                HttpMethod.PUT,
                HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(callerToken)),
                String::class.java,
            )
            assertEquals(HttpStatus.FORBIDDEN, workingHoursResponse.statusCode)

            val createCustomerResponse = restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/customers"),
                HttpMethod.POST,
                HttpEntity(CreateCustomerRequest("Someone", "+989123456780", null, null), bearer(callerToken)),
                String::class.java,
            )
            assertEquals(HttpStatus.FORBIDDEN, createCustomerResponse.statusCode)

            val assignServiceResponse = restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/services/${service.id}"),
                HttpMethod.PUT,
                HttpEntity<Void>(bearer(callerToken)),
                String::class.java,
            )
            assertEquals(HttpStatus.FORBIDDEN, assignServiceResponse.statusCode)
        }
    }

    // ---- 4. Booking lifecycle ----

    @Test
    fun `booking lifecycle - legal transitions succeed and illegal transitions are rejected`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER, "Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val service = createService(ownerToken, salon.id, hair.id, "Women's Haircut", 45, BigDecimal("45.00"))
        val specialist = createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        val monday = nextDayOfWeek(DayOfWeek.MONDAY)
        setAvailability(ownerToken, salon.id, specialist.id, DayOfWeek.MONDAY)
        activateSalon(ownerToken, salon.id)

        val (customerToken, customerUserId, phone) = otpRegisterAndLogin("Mina First-Timer")
        val customer = customerRepository.save(
            Customer.create(SalonId(salon.id), UserId(customerUserId), "Mina First-Timer", PhoneNumber(phone), null, null),
        )

        val slot = firstAvailableSlot(customerToken, salon.id, specialist.id, service.id, monday)
        val booking = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/bookings"),
                HttpMethod.POST,
                HttpEntity(CreateBookingForCustomerRequest(customer.id.value, service.id, specialist.id, slot.start, "First visit"), bearer(ownerToken)),
                BookingResponse::class.java,
            ).body,
        )
        assertEquals(BookingStatus.PENDING, booking.status)

        val completeBeforeConfirm = restTemplate.exchange(
            url("/api/v1/bookings/${booking.id}/complete"), HttpMethod.PATCH, HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, completeBeforeConfirm.statusCode)

        val confirmed = requireNotNull(
            restTemplate.exchange(url("/api/v1/bookings/${booking.id}/confirm"), HttpMethod.PATCH, HttpEntity<Void>(bearer(ownerToken)), BookingResponse::class.java).body,
        )
        assertEquals(BookingStatus.CONFIRMED, confirmed.status)

        val completed = requireNotNull(
            restTemplate.exchange(url("/api/v1/bookings/${booking.id}/complete"), HttpMethod.PATCH, HttpEntity<Void>(bearer(ownerToken)), BookingResponse::class.java).body,
        )
        assertEquals(BookingStatus.COMPLETED, completed.status)

        val cancelAfterComplete = restTemplate.exchange(
            url("/api/v1/bookings/${booking.id}/cancel"), HttpMethod.PATCH, HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, cancelAfterComplete.statusCode)
    }

    // ---- 5. Specialist-service eligibility ----

    @Test
    fun `specialist-service eligibility is enforced end-to-end`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER, "Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val nails = createCategory(ownerToken, salon.id, "Nails")
        val haircut = createService(ownerToken, salon.id, hair.id, "Women's Haircut", 45, BigDecimal("45.00"))
        val gelPolish = createService(ownerToken, salon.id, nails.id, "Gel Polish", 45, BigDecimal("35.00"))
        val nazanin = createSpecialist(ownerToken, salon.id, "Nazanin Rezaei")
        val monday = nextDayOfWeek(DayOfWeek.MONDAY)
        setAvailability(ownerToken, salon.id, nazanin.id, DayOfWeek.MONDAY)
        activateSalon(ownerToken, salon.id)

        // Backward-compatible default: no assignment yet, bookable for anything.
        val slotsBeforeAssignment = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists/${nazanin.id}/available-slots?serviceId=${haircut.id}&date=$monday&slotIntervalMinutes=30"),
                HttpMethod.GET,
                HttpEntity<Void>(bearer(ownerToken)),
                Array<TimeSlotResponse>::class.java,
            ).body,
        )
        assertTrue(slotsBeforeAssignment.isNotEmpty(), "specialist with no assignments must remain bookable for anything")

        // Opt in: Nazanin now does nails only.
        assignService(ownerToken, salon.id, nazanin.id, gelPolish.id)

        val slotsForUnassignedService = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists/${nazanin.id}/available-slots?serviceId=${haircut.id}&date=$monday&slotIntervalMinutes=30"),
                HttpMethod.GET,
                HttpEntity<Void>(bearer(ownerToken)),
                Array<TimeSlotResponse>::class.java,
            ).body,
        )
        assertTrue(slotsForUnassignedService.isEmpty(), "once assigned, availability for an unassigned service must be empty")

        val (customerToken, customerUserId, phone) = otpRegisterAndLogin("Parisa Nails")
        val customer = customerRepository.save(
            Customer.create(SalonId(salon.id), UserId(customerUserId), "Parisa Nails", PhoneNumber(phone), null, null),
        )
        val slot = firstAvailableSlot(customerToken, salon.id, nazanin.id, gelPolish.id, monday)

        val eligibleBooking = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"),
            HttpMethod.POST,
            HttpEntity(CreateBookingForCustomerRequest(customer.id.value, gelPolish.id, nazanin.id, slot.start, null), bearer(ownerToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, eligibleBooking.statusCode)

        val ineligibleBooking = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"),
            HttpMethod.POST,
            HttpEntity(CreateBookingForCustomerRequest(customer.id.value, haircut.id, nazanin.id, slot.start.plusHours(3), null), bearer(ownerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, ineligibleBooking.statusCode)
        assertTrue(ineligibleBooking.body!!.contains("SPECIALIST_NOT_ELIGIBLE_FOR_SERVICE"))
    }

    // ---- 6. Customer history ----

    @Test
    fun `a linked customer's completed booking updates their timeline and lifetime value`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER, "Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val hair = createCategory(ownerToken, salon.id, "Hair")
        val service = createService(ownerToken, salon.id, hair.id, "Women's Haircut", 45, BigDecimal("45.00"))
        val specialist = createSpecialist(ownerToken, salon.id, "Mariam Karimi")
        val monday = nextDayOfWeek(DayOfWeek.MONDAY)
        setAvailability(ownerToken, salon.id, specialist.id, DayOfWeek.MONDAY)
        activateSalon(ownerToken, salon.id)

        val (_, customerUserId, phone) = otpRegisterAndLogin("Parisa Walk-in")
        val customer = customerRepository.save(
            Customer.create(SalonId(salon.id), UserId(customerUserId), "Parisa Walk-in", PhoneNumber(phone), null, null),
        )

        val slot = firstAvailableSlot(ownerToken, salon.id, specialist.id, service.id, monday)
        val booking = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/bookings"),
                HttpMethod.POST,
                HttpEntity(CreateBookingForCustomerRequest(customer.id.value, service.id, specialist.id, slot.start, null), bearer(ownerToken)),
                BookingResponse::class.java,
            ).body,
        )
        restTemplate.exchange(url("/api/v1/bookings/${booking.id}/confirm"), HttpMethod.PATCH, HttpEntity<Void>(bearer(ownerToken)), BookingResponse::class.java)
        restTemplate.exchange(url("/api/v1/bookings/${booking.id}/complete"), HttpMethod.PATCH, HttpEntity<Void>(bearer(ownerToken)), BookingResponse::class.java)

        val timeline = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customers/${customer.id.value}/timeline"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<CustomerTimelineEntryResponse>>() {},
        ).body
        assertTrue(timeline!!.content.any { it.type == "BOOKING_CREATED" })
        assertTrue(timeline.content.any { it.type == "BOOKING_COMPLETED" })

        val customerResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customers/${customer.id.value}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            CustomerResponse::class.java,
        ).body
        assertEquals(0, BigDecimal("45.00").compareTo(customerResponse!!.lifetimeValue))
    }

    // ---- 7. Tenant isolation / data isolation validation ----

    @Test
    fun `cross-tenant data never leaks between two salons, including specialist-service assignment`() {
        val (ownerAToken, _) = registerAndLogin(UserRole.MANAGER, "Sara Ahmadi")
        val salonA = createSalon(ownerAToken, "Rojan Beauty Studio")
        val hairA = createCategory(ownerAToken, salonA.id, "Hair")
        val serviceA = createService(ownerAToken, salonA.id, hairA.id, "Women's Haircut", 45, BigDecimal("45.00"))
        val specialistA = createSpecialist(ownerAToken, salonA.id, "Mariam Karimi")
        setAvailability(ownerAToken, salonA.id, specialistA.id, DayOfWeek.MONDAY)
        activateSalon(ownerAToken, salonA.id)
        val (_, customerAUserId, phoneA) = otpRegisterAndLogin("Customer A")
        val customerA = customerRepository.save(Customer.create(SalonId(salonA.id), UserId(customerAUserId), "Customer A", PhoneNumber(phoneA), null, null))

        val (ownerBToken, _) = registerAndLogin(UserRole.MANAGER, "Velvet Owner")
        val salonB = createSalon(ownerBToken, "Velvet & Co")
        val nailsB = createCategory(ownerBToken, salonB.id, "Nails")
        val serviceB = createService(ownerBToken, salonB.id, nailsB.id, "Manicure", 30, BigDecimal("15.00"))
        val (_, customerBUserId, phoneB) = otpRegisterAndLogin("Customer B")
        val customerB = customerRepository.save(Customer.create(SalonId(salonB.id), UserId(customerBUserId), "Customer B", PhoneNumber(phoneB), null, null))

        // Salon B's owner cannot read Salon A's customer.
        val crossTenantGet = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/customers/${customerA.id.value}"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerBToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantGet.statusCode)

        // Salon A's owner cannot book Salon A's customer against Salon B's service/specialist.
        val crossTenantBooking = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/bookings"),
            HttpMethod.POST,
            HttpEntity(CreateBookingForCustomerRequest(customerA.id.value, serviceB.id, specialistA.id, LocalDate.now().plusDays(20).atTime(10, 0), null), bearer(ownerAToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantBooking.statusCode)

        // Salon A's owner cannot assign Salon B's service to Salon A's specialist - the eligibility-specific isolation guarantee.
        val crossTenantAssign = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/specialists/${specialistA.id}/services/${serviceB.id}"),
            HttpMethod.PUT,
            HttpEntity<Void>(bearer(ownerAToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantAssign.statusCode)

        // List endpoints never leak the other salon's rows.
        val salonACustomers = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/customers"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerAToken)),
            object : ParameterizedTypeReference<PagedResponse<CustomerResponse>>() {},
        ).body
        assertTrue(salonACustomers!!.content.all { it.salonId == salonA.id })
        assertFalse(salonACustomers.content.any { it.id == customerB.id.value })
    }
}
