package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.payment.PaymentGatewayConfigResponse
import ai.rojan.backend.api.payment.PaymentGatewayTestResponse
import ai.rojan.backend.api.payment.SavePaymentGatewayConfigRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.payment.PaymentGatewayProvider
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * P1 Per-Salon Payment Gateway Configuration - real HTTP, real Spring Security, real (embedded)
 * Postgres, same convention as [PaymentControllerIntegrationTest]. [DirtiesContext] for the same
 * reason that file's own doc comment gives: a fresh context isolates this class from any shared-
 * test-context rate-limiter/singleton-bean state left over by classes that ran before it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PaymentGatewayConfigControllerIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var salonMembershipRepository: SalonMembershipRepository

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun currentUserIdOf(token: String): UserId {
        val me = requireNotNull(
            restTemplate.exchange(url("/api/v1/users/me"), HttpMethod.GET, HttpEntity<Void>(bearer(token)), UserResponse::class.java).body,
        )
        return UserId(me.id)
    }

    private fun registerAndLogin(role: UserRole): String {
        val email = "payment-gateway.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Test $role", role = role),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    private data class SalonFixture(val ownerToken: String, val salonId: UUID)

    private fun createSalon(): SalonFixture {
        val ownerToken = registerAndLogin(UserRole.MANAGER)
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"), HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Gateway Config Salon ${System.nanoTime()}", null, "09120000000", null, "Tehran"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        return SalonFixture(ownerToken, salon.id)
    }

    private fun saveConfig(salonId: UUID, token: String, merchantId: String = "a1b2c3d4-e5f6-7890-abcd-ef${System.nanoTime()}".take(36)) =
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/payment-gateway"), HttpMethod.PUT,
            HttpEntity(SavePaymentGatewayConfigRequest(PaymentGatewayProvider.ZARINPAL, merchantId), bearer(token)),
            PaymentGatewayConfigResponse::class.java,
        )

    // ---------------- get ----------------

    @Test
    fun `get returns configured=false for a salon that has never saved a config`() {
        val fixture = createSalon()

        val response = restTemplate.exchange(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(fixture.ownerToken)), PaymentGatewayConfigResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertFalse(requireNotNull(response.body).configured)
        assertNull(response.body!!.maskedMerchantId)
    }

    @Test
    fun `a stranger (not the owner) cannot read a salon's payment gateway config - 403`() {
        val fixture = createSalon()
        val stranger = registerAndLogin(UserRole.MANAGER)

        val response = restTemplate.exchange(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(stranger)), ApiError::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("ACCESS_DENIED", response.body!!.errorCode)
    }

    // ---------------- save (PUT) ----------------

    @Test
    fun `save never returns the plaintext merchant id in the response - only a masked preview`() {
        val fixture = createSalon()

        val response = saveConfig(fixture.salonId, fixture.ownerToken, merchantId = "the-real-plaintext-merchant-id-00000")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = requireNotNull(response.body)
        assertTrue(body.configured)
        assertFalse(body.maskedMerchantId!!.contains("the-real-plaintext-merchant-id-00000"))
        assertEquals(PaymentGatewayProvider.ZARINPAL, body.provider)
        assertFalse(body.isActive)
    }

    @Test
    fun `a newly-saved config does not round-trip the plaintext through a subsequent GET either`() {
        val fixture = createSalon()
        saveConfig(fixture.salonId, fixture.ownerToken, merchantId = "another-real-plaintext-merchant-id0")

        val getResponse = restTemplate.exchange(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(fixture.ownerToken)), PaymentGatewayConfigResponse::class.java,
        )

        val body = requireNotNull(getResponse.body)
        assertTrue(body.configured)
        assertFalse(body.maskedMerchantId!!.contains("another-real-plaintext-merchant-id0"))
    }

    @Test
    fun `a stranger (not the owner) cannot save a payment gateway config for someone else's salon - 403`() {
        val fixture = createSalon()
        val stranger = registerAndLogin(UserRole.MANAGER)

        val response = saveConfig(fixture.salonId, stranger)
        val error = restTemplate.exchange(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway"), HttpMethod.PUT,
            HttpEntity(SavePaymentGatewayConfigRequest(PaymentGatewayProvider.ZARINPAL, "attacker-merchant-id-0000000000000"), bearer(stranger)),
            ApiError::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("ACCESS_DENIED", error.body!!.errorCode)
    }

    // ---------------- activate / deactivate ----------------

    @Test
    fun `activate then deactivate round-trips isActive via real HTTP`() {
        val fixture = createSalon()
        saveConfig(fixture.salonId, fixture.ownerToken)

        val activated = restTemplate.postForEntity(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway/activate"),
            HttpEntity<Void>(bearer(fixture.ownerToken)),
            PaymentGatewayConfigResponse::class.java,
        )
        assertTrue(requireNotNull(activated.body).isActive)

        val deactivated = restTemplate.postForEntity(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway/deactivate"),
            HttpEntity<Void>(bearer(fixture.ownerToken)),
            PaymentGatewayConfigResponse::class.java,
        )
        assertFalse(requireNotNull(deactivated.body).isActive)
    }

    @Test
    fun `activate on a salon with no saved config returns 404, not a silent no-op`() {
        val fixture = createSalon()

        val response = restTemplate.postForEntity(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway/activate"),
            HttpEntity<Void>(bearer(fixture.ownerToken)),
            ApiError::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("PAYMENT_GATEWAY_CONFIG_NOT_FOUND", response.body!!.errorCode)
    }

    // ---------------- test connection ----------------

    @Test
    fun `test connection against the real, test-profile FakePaymentGatewayAdapter succeeds for a saved credential`() {
        val fixture = createSalon()
        saveConfig(fixture.salonId, fixture.ownerToken)

        val response = restTemplate.postForEntity(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway/test"),
            HttpEntity<Void>(bearer(fixture.ownerToken)),
            PaymentGatewayTestResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(requireNotNull(response.body).success)
    }

    // ---------------- isolation between salons ----------------

    @Test
    fun `salon A's owner cannot see or affect salon B's config at all - fully independent HTTP resources`() {
        val salonA = createSalon()
        val salonB = createSalon()
        saveConfig(salonB.salonId, salonB.ownerToken, merchantId = "salon-b-only-merchant-id-0000000000")

        val crossGet = restTemplate.exchange(
            url("/api/v1/salons/${salonB.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(salonA.ownerToken)), ApiError::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, crossGet.statusCode)

        // Salon A's own config remains genuinely unconfigured - salon B's save never leaked across.
        val ownGet = restTemplate.exchange(
            url("/api/v1/salons/${salonA.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(salonA.ownerToken)), PaymentGatewayConfigResponse::class.java,
        )
        assertFalse(requireNotNull(ownGet.body).configured)
    }

    // ---------------- manager role (correction: MANAGE_PAYMENT_GATEWAY granted to MANAGER) ----------------

    @Test
    fun `a salon member with the MANAGER role can GET and PUT (save) that salon's own payment gateway config`() {
        val fixture = createSalon()
        val managerToken = registerAndLogin(UserRole.MANAGER)
        salonMembershipRepository.assign(SalonId(fixture.salonId), currentUserIdOf(managerToken), SalonRole.MANAGER)

        val getBeforeSave = restTemplate.exchange(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)), PaymentGatewayConfigResponse::class.java,
        )
        assertEquals(HttpStatus.OK, getBeforeSave.statusCode)
        assertFalse(requireNotNull(getBeforeSave.body).configured)

        val putResponse = restTemplate.exchange(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway"), HttpMethod.PUT,
            HttpEntity(SavePaymentGatewayConfigRequest(PaymentGatewayProvider.ZARINPAL, "manager-saved-merchant-id-00000000"), bearer(managerToken)),
            PaymentGatewayConfigResponse::class.java,
        )
        assertEquals(HttpStatus.OK, putResponse.statusCode)
        assertTrue(requireNotNull(putResponse.body).configured)
    }

    @Test
    fun `a salon member with the MANAGER role can activate, deactivate, and test that salon's own payment gateway config`() {
        val fixture = createSalon()
        val managerToken = registerAndLogin(UserRole.MANAGER)
        salonMembershipRepository.assign(SalonId(fixture.salonId), currentUserIdOf(managerToken), SalonRole.MANAGER)
        saveConfig(fixture.salonId, managerToken)

        val activated = restTemplate.postForEntity(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway/activate"),
            HttpEntity<Void>(bearer(managerToken)),
            PaymentGatewayConfigResponse::class.java,
        )
        assertEquals(HttpStatus.OK, activated.statusCode)
        assertTrue(requireNotNull(activated.body).isActive)

        val deactivated = restTemplate.postForEntity(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway/deactivate"),
            HttpEntity<Void>(bearer(managerToken)),
            PaymentGatewayConfigResponse::class.java,
        )
        assertEquals(HttpStatus.OK, deactivated.statusCode)
        assertFalse(requireNotNull(deactivated.body).isActive)

        val tested = restTemplate.postForEntity(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway/test"),
            HttpEntity<Void>(bearer(managerToken)),
            PaymentGatewayTestResponse::class.java,
        )
        assertEquals(HttpStatus.OK, tested.statusCode)
        assertTrue(requireNotNull(tested.body).success)
    }

    @Test
    fun `a MANAGER of salon A still gets 403 ACCESS_DENIED for salon B's payment gateway config - membership never crosses salons`() {
        val salonA = createSalon()
        val salonB = createSalon()
        val managerOfA = registerAndLogin(UserRole.MANAGER)
        salonMembershipRepository.assign(SalonId(salonA.salonId), currentUserIdOf(managerOfA), SalonRole.MANAGER)
        saveConfig(salonB.salonId, salonB.ownerToken, merchantId = "salon-b-protected-merchant-id-0000")

        val crossGet = restTemplate.exchange(
            url("/api/v1/salons/${salonB.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(managerOfA)), ApiError::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, crossGet.statusCode)
        assertEquals("ACCESS_DENIED", crossGet.body!!.errorCode)

        val crossPut = restTemplate.exchange(
            url("/api/v1/salons/${salonB.salonId}/payment-gateway"), HttpMethod.PUT,
            HttpEntity(SavePaymentGatewayConfigRequest(PaymentGatewayProvider.ZARINPAL, "attacker-merchant-id-0000000000000"), bearer(managerOfA)),
            ApiError::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, crossPut.statusCode)
        assertEquals("ACCESS_DENIED", crossPut.body!!.errorCode)
    }

    @Test
    fun `the owner's own-salon access still works exactly as before this correction - GET and PUT both 200`() {
        val fixture = createSalon()

        val putResponse = saveConfig(fixture.salonId, fixture.ownerToken)
        assertEquals(HttpStatus.OK, putResponse.statusCode)

        val getResponse = restTemplate.exchange(
            url("/api/v1/salons/${fixture.salonId}/payment-gateway"), HttpMethod.GET,
            HttpEntity<Void>(bearer(fixture.ownerToken)), PaymentGatewayConfigResponse::class.java,
        )
        assertEquals(HttpStatus.OK, getResponse.statusCode)
        assertTrue(requireNotNull(getResponse.body).configured)
    }
}
