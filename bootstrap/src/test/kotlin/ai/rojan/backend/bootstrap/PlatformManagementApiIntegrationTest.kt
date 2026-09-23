package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.customer.AddCustomerNoteRequest
import ai.rojan.backend.api.customer.CreateCustomerRequest
import ai.rojan.backend.api.customer.CustomerResponse
import ai.rojan.backend.api.platformauthority.ManagerSalonAssociationResponse
import ai.rojan.backend.api.platformauthority.PlatformCustomerAccountResponse
import ai.rojan.backend.api.platformauthority.PlatformManagerMutationResponse
import ai.rojan.backend.api.platformauthority.PlatformManagerResponse
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonMembershipResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.application.platformauthority.ManagerSalonAccessType
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.salon.SalonRole
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
import java.util.UUID

/**
 * Platform Management API Contract (Manager/Customer) - real HTTP + embedded Postgres, same pattern
 * as [PlatformAuthorityFlowIntegrationTest]. Self-contained (its own local helpers) rather than
 * extending that file, matching this test directory's existing one-file-per-feature convention.
 *
 * Covers: PLATFORM_ADMIN/PLATFORM_REVIEWER read access and non-platform denial for both
 * `/platform-authority/managers` and `/platform-authority/customers`; pagination and name/phone
 * search; real manager salon-association resolution (owner vs member, correct [SalonRole]);
 * PLATFORM_ADMIN-only deactivate/reactivate (blocks/restores login, never touches salon
 * activation); and, critically, that no salon-private CRM `Customer` data ever appears in the
 * platform CUSTOMER account response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PlatformManagementApiIntegrationTest {

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

    private fun randomPhone() = "+9892${(1_000_000..9_999_999).random()}"

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

    /** Seeds a real, active platform-role account directly (no HTTP path to self-create one, by design) and logs in via the real OTP flow, exactly like [PlatformAuthorityFlowIntegrationTest.seedPlatformAdmin]. */
    private fun seedPlatformUser(role: UserRole, fullName: String): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), fullName, role))
        return loginViaOtp(phone)
    }

    private fun registerAndLogin(role: UserRole, fullName: String): Pair<String, UUID> {
        val email = "platmgmt.${System.nanoTime()}@example.com"
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

    private fun createSalon(ownerToken: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"), HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    private fun getSalon(token: String, salonId: UUID): SalonResponse = requireNotNull(
        restTemplate.exchange(url("/api/v1/salons/$salonId"), HttpMethod.GET, HttpEntity<Void>(bearer(token)), SalonResponse::class.java).body,
    )

    private fun assignMembership(ownerToken: String, salonId: UUID, userId: UUID, role: SalonRole): SalonMembershipResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/members/$userId"), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(role), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        ).body,
    )

    private fun listManagers(
        token: String,
        page: Int = 0,
        size: Int = 20,
        search: String? = null,
    ) = restTemplate.exchange(
        url("/api/v1/platform-authority/managers?page=$page&size=$size" + (search?.let { "&search=$it" } ?: "")),
        HttpMethod.GET, HttpEntity<Void>(bearer(token)), Map::class.java,
    )

    private fun listCustomerAccounts(
        token: String,
        page: Int = 0,
        size: Int = 20,
        search: String? = null,
    ) = restTemplate.exchange(
        url("/api/v1/platform-authority/customers?page=$page&size=$size" + (search?.let { "&search=$it" } ?: "")),
        HttpMethod.GET, HttpEntity<Void>(bearer(token)), String::class.java,
    )

    // ---- Manager: read access -----------------------------------------------------------------

    @Test
    fun `PLATFORM_ADMIN and PLATFORM_REVIEWER can both list MANAGER accounts, a non-platform caller cannot`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin One")
        val reviewerToken = seedPlatformUser(UserRole.PLATFORM_REVIEWER, "Reviewer One")
        val (managerToken, _) = registerAndLogin(UserRole.MANAGER, "Plain Manager")

        assertEquals(HttpStatus.OK, listManagers(adminToken).statusCode)
        assertEquals(HttpStatus.OK, listManagers(reviewerToken).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, listManagers(managerToken).statusCode)
    }

    @Test
    fun `a real manager's salon associations reflect real ownership and real membership, never fabricated or missing`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Two")
        val (ownerToken, ownerId) = registerAndLogin(UserRole.MANAGER, "Owner Manager ${System.nanoTime()}")
        val ownedSalon = createSalon(ownerToken, "Owned Salon ${System.nanoTime()}")

        val (otherOwnerToken, _) = registerAndLogin(UserRole.MANAGER, "Other Owner ${System.nanoTime()}")
        val memberSalon = createSalon(otherOwnerToken, "Member Salon ${System.nanoTime()}")
        assignMembership(otherOwnerToken, memberSalon.id, ownerId, SalonRole.MANAGER)

        val response = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/managers?size=100"), HttpMethod.GET,
                HttpEntity<Void>(bearer(adminToken)), PagedManagerResponse::class.java,
            ).body,
        )

        val found = response.content.single { it.id == ownerId }
        val associations = found.salonAssociations
        assertTrue(associations.any { it.salonId == ownedSalon.id && it.accessType == ManagerSalonAccessType.OWNER && it.role == null })
        assertTrue(associations.any { it.salonId == memberSalon.id && it.accessType == ManagerSalonAccessType.MEMBER && it.role == SalonRole.MANAGER })
        // Cross-salon privacy: this manager has no association at all with a third, unrelated salon.
        val unrelatedSalon = createSalon(otherOwnerToken, "Unrelated Salon ${System.nanoTime()}")
        assertFalse(associations.any { it.salonId == unrelatedSalon.id })
    }

    @Test
    fun `search filters MANAGER accounts by a real name substring`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Three")
        val distinctiveName = "Zzyzx Manager ${System.nanoTime()}"
        registerAndLogin(UserRole.MANAGER, distinctiveName)
        registerAndLogin(UserRole.MANAGER, "Someone Else ${System.nanoTime()}")

        val response = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/managers?search=Zzyzx"), HttpMethod.GET,
                HttpEntity<Void>(bearer(adminToken)), PagedManagerResponse::class.java,
            ).body,
        )

        assertTrue(response.content.isNotEmpty())
        assertTrue(response.content.all { it.fullName.contains("Zzyzx") })
    }

    @Test
    fun `pagination reports the real totalElements and honors the requested page size for MANAGER accounts`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Four")
        val marker = "PageMarker${System.nanoTime()}"
        repeat(3) { registerAndLogin(UserRole.MANAGER, "$marker Manager $it") }

        val firstPage = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/managers?search=$marker&page=0&size=2"), HttpMethod.GET,
                HttpEntity<Void>(bearer(adminToken)), PagedManagerResponse::class.java,
            ).body,
        )

        assertEquals(2, firstPage.content.size)
        assertEquals(3L, firstPage.totalElements)
        assertEquals(2, firstPage.totalPages)
    }

    // ---- Manager: deactivate/reactivate ---------------------------------------------------------

    @Test
    fun `PLATFORM_ADMIN can deactivate and reactivate a MANAGER account - blocks and restores login without touching the salon they own`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Five")
        val managerEmail = "platmgmt.mgr.${System.nanoTime()}@example.com"
        val registered = requireNotNull(
            restTemplate.postForEntity(
                url("/api/v1/auth/register"),
                RegisterRequest(email = managerEmail, password = "supersecret123", fullName = "Deactivate Me", role = UserRole.MANAGER),
                UserResponse::class.java,
            ).body,
        )
        val loginRequest = LoginRequest(email = managerEmail, password = "supersecret123")
        val firstLogin = requireNotNull(restTemplate.postForEntity(url("/api/v1/auth/login"), loginRequest, AuthResponse::class.java).body)
        val ownedSalon = createSalon(firstLogin.accessToken, "Still Active Salon ${System.nanoTime()}")

        val deactivateResponse = restTemplate.exchange(
            url("/api/v1/platform-authority/managers/${registered.id}/deactivate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), PlatformManagerMutationResponse::class.java,
        )
        assertEquals(HttpStatus.OK, deactivateResponse.statusCode)
        assertFalse(requireNotNull(deactivateResponse.body).active)

        val loginAfterDeactivate = restTemplate.postForEntity(url("/api/v1/auth/login"), loginRequest, String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, loginAfterDeactivate.statusCode)

        // Deactivating the manager's account never cascades to the salon they own.
        val salonAfterDeactivate = getSalon(adminToken, ownedSalon.id)
        assertTrue(salonAfterDeactivate.active)

        val reactivateResponse = restTemplate.exchange(
            url("/api/v1/platform-authority/managers/${registered.id}/reactivate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), PlatformManagerMutationResponse::class.java,
        )
        assertEquals(HttpStatus.OK, reactivateResponse.statusCode)
        assertTrue(requireNotNull(reactivateResponse.body).active)

        val loginAfterReactivate = restTemplate.postForEntity(url("/api/v1/auth/login"), loginRequest, AuthResponse::class.java)
        assertEquals(HttpStatus.OK, loginAfterReactivate.statusCode)
    }

    @Test
    fun `PLATFORM_REVIEWER cannot deactivate or reactivate a MANAGER account - only PLATFORM_ADMIN can`() {
        val reviewerToken = seedPlatformUser(UserRole.PLATFORM_REVIEWER, "Reviewer Two")
        val (_, managerId) = registerAndLogin(UserRole.MANAGER, "Untouchable Manager")

        val deactivate = restTemplate.exchange(
            url("/api/v1/platform-authority/managers/$managerId/deactivate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(reviewerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, deactivate.statusCode)
    }

    // ---- Customer accounts: read access ---------------------------------------------------------

    @Test
    fun `PLATFORM_ADMIN and PLATFORM_REVIEWER can both list CUSTOMER accounts, a non-platform caller cannot`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Six")
        val reviewerToken = seedPlatformUser(UserRole.PLATFORM_REVIEWER, "Reviewer Three")
        val (customerToken, _) = registerAndLogin(UserRole.CUSTOMER, "Plain Customer")

        assertEquals(HttpStatus.OK, listCustomerAccounts(adminToken).statusCode)
        assertEquals(HttpStatus.OK, listCustomerAccounts(reviewerToken).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, listCustomerAccounts(customerToken).statusCode)
    }

    @Test
    fun `search and pagination work identically for CUSTOMER accounts`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Seven")
        val marker = "CustMarker${System.nanoTime()}"
        repeat(2) { registerAndLogin(UserRole.CUSTOMER, "$marker Customer $it") }

        val response = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/customers?search=$marker&page=0&size=1"), HttpMethod.GET,
                HttpEntity<Void>(bearer(adminToken)), PagedCustomerAccountResponse::class.java,
            ).body,
        )

        assertEquals(1, response.content.size)
        assertEquals(2L, response.totalElements)
        assertTrue(response.content.single().fullName.contains(marker))
    }

    @Test
    fun `salon-private CRM customer data never appears in the platform CUSTOMER account listing`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Eight")
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER, "CRM Owner ${System.nanoTime()}")
        val salon = createSalon(ownerToken, "CRM Salon ${System.nanoTime()}")

        val crmCustomer = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/customers"), HttpMethod.POST,
                HttpEntity(CreateCustomerRequest("Real CRM Person", "+15550199", null, "Acme Corp"), bearer(ownerToken)),
                CustomerResponse::class.java,
            ).body,
        )
        val secretNoteText = "SECRET_CRM_NOTE_${System.nanoTime()}"
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customers/${crmCustomer.id}/notes"), HttpMethod.POST,
            HttpEntity(AddCustomerNoteRequest(secretNoteText), bearer(ownerToken)), String::class.java,
        )

        val rawResponse = requireNotNull(listCustomerAccounts(adminToken).body)

        assertFalse(rawResponse.contains(secretNoteText), "CRM note text leaked into the platform customer account listing")
        assertFalse(rawResponse.contains("Acme Corp"), "CRM company field leaked into the platform customer account listing")
        assertFalse(rawResponse.contains("lifetimeValue", ignoreCase = true), "CRM lifetime value field leaked into the platform customer account listing")
        assertFalse(rawResponse.contains("\"notes\""), "a CRM notes field leaked into the platform customer account listing")
        assertFalse(rawResponse.contains("\"tags\""), "a CRM tags field leaked into the platform customer account listing")
    }

    @Test
    fun `PLATFORM_ADMIN can deactivate and reactivate a CUSTOMER account, PLATFORM_REVIEWER cannot`() {
        val adminToken = seedPlatformUser(UserRole.PLATFORM_ADMIN, "Admin Nine")
        val reviewerToken = seedPlatformUser(UserRole.PLATFORM_REVIEWER, "Reviewer Four")
        val (_, customerId) = registerAndLogin(UserRole.CUSTOMER, "Deactivatable Customer")

        val deniedForReviewer = restTemplate.exchange(
            url("/api/v1/platform-authority/customers/$customerId/deactivate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(reviewerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, deniedForReviewer.statusCode)

        val deactivate = restTemplate.exchange(
            url("/api/v1/platform-authority/customers/$customerId/deactivate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), PlatformCustomerAccountResponse::class.java,
        )
        assertEquals(HttpStatus.OK, deactivate.statusCode)
        assertFalse(requireNotNull(deactivate.body).active)

        val reactivate = restTemplate.exchange(
            url("/api/v1/platform-authority/customers/$customerId/reactivate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), PlatformCustomerAccountResponse::class.java,
        )
        assertEquals(HttpStatus.OK, reactivate.statusCode)
        assertTrue(requireNotNull(reactivate.body).active)
    }

    private data class PagedManagerResponse(
        val content: List<PlatformManagerResponse> = emptyList(),
        val page: Int = 0,
        val size: Int = 0,
        val totalElements: Long = 0,
        val totalPages: Int = 0,
    )

    private data class PagedCustomerAccountResponse(
        val content: List<PlatformCustomerAccountResponse> = emptyList(),
        val page: Int = 0,
        val size: Int = 0,
        val totalElements: Long = 0,
        val totalPages: Int = 0,
    )
}
