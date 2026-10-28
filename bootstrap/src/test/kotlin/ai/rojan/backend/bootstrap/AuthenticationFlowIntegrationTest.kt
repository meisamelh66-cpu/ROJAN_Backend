package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RefreshRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * End-to-end verification of the auth vertical slice against a real
 * (embedded, no-Docker) PostgreSQL and the actual HTTP layer — the same
 * shape of calls an Android client would make.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// This environment has no Docker daemon — force the native, process-based
// provider (the one backed by embedded-postgres-binaries) rather than the
// library's default, which probes for Docker first.
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class AuthenticationFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String): HttpEntity<Void> =
        HttpEntity(HttpHeaders().apply { setBearerAuth(token) })

    @Test
    fun `register, login, refresh, and authenticated access all work end-to-end`() {
        val email = "integration.${System.nanoTime()}@example.com"

        val registerResponse = restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Integration Test", role = UserRole.CUSTOMER),
            UserResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, registerResponse.statusCode)
        assertEquals(email, registerResponse.body?.email)
        assertEquals(UserRole.CUSTOMER, registerResponse.body?.role)

        val loginResponse = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        assertEquals(HttpStatus.OK, loginResponse.statusCode)
        val tokens = requireNotNull(loginResponse.body)
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())

        val meResponse = restTemplate.exchange(
            url("/api/v1/users/me"),
            HttpMethod.GET,
            bearer(tokens.accessToken),
            UserResponse::class.java,
        )
        assertEquals(HttpStatus.OK, meResponse.statusCode)
        assertEquals(email, meResponse.body?.email)

        val refreshResponse = restTemplate.postForEntity(
            url("/api/v1/auth/refresh"),
            RefreshRequest(refreshToken = tokens.refreshToken),
            AuthResponse::class.java,
        )
        assertEquals(HttpStatus.OK, refreshResponse.statusCode)
        val refreshedTokens = requireNotNull(refreshResponse.body)
        assertTrue(refreshedTokens.accessToken.isNotBlank())
        assertNotEquals(tokens.accessToken, refreshedTokens.accessToken)

        val meWithRefreshedToken = restTemplate.exchange(
            url("/api/v1/users/me"),
            HttpMethod.GET,
            bearer(refreshedTokens.accessToken),
            UserResponse::class.java,
        )
        assertEquals(HttpStatus.OK, meWithRefreshedToken.statusCode)
        assertEquals(email, meWithRefreshedToken.body?.email)
    }

    @Test
    fun `rejects registering the same email twice`() {
        val email = "dup.${System.nanoTime()}@example.com"
        val request = RegisterRequest(email = email, password = "supersecret123", fullName = "Dup", role = UserRole.CUSTOMER)

        restTemplate.postForEntity(url("/api/v1/auth/register"), request, UserResponse::class.java)
        val second = restTemplate.postForEntity(url("/api/v1/auth/register"), request, String::class.java)

        assertEquals(HttpStatus.CONFLICT, second.statusCode)
    }

    // ROJAN_BACKEND_SECURITY_AUDIT_v1.md F5 / GHSA-mg83-c7gq-rv5c: BCrypt's real
    // boundary (confirmed empirically, BCryptPasswordEncoderAdapterTest) is 72
    // *UTF-8 bytes*, not 72 characters - a password past it used to be accepted
    // at registration (RegisterRequest's own Size constraint allowed up to 128
    // characters) and, on an unpatched spring-security-crypto, could let a
    // caller authenticate knowing only the first 72 bytes. Validated here at
    // the real HTTP layer via @Utf8ByteSize, for both ASCII and multibyte
    // UTF-8 (Persian/Farsi - ROJAN is an RTL-Persian-first platform) input.
    @Test
    fun `rejects registration with an ASCII password one byte past the 72-byte boundary`() {
        val email = "longpass.${System.nanoTime()}@example.com"
        val request = RegisterRequest(email = email, password = "a".repeat(73), fullName = "Long Pass", role = UserRole.CUSTOMER)

        val response = restTemplate.postForEntity(url("/api/v1/auth/register"), request, String::class.java)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `accepts registration with an ASCII password exactly at the 72-byte boundary`() {
        val email = "maxpass.${System.nanoTime()}@example.com"
        val request = RegisterRequest(email = email, password = "a".repeat(72), fullName = "Max Pass", role = UserRole.CUSTOMER)

        val response = restTemplate.postForEntity(url("/api/v1/auth/register"), request, UserResponse::class.java)

        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `rejects registration with a 72-character Farsi password that is 144 UTF-8 bytes`() {
        val email = "farsitoolong.${System.nanoTime()}@example.com"
        // "سلام" = 4 chars = 8 UTF-8 bytes; 18 repeats = 72 chars, 144 bytes.
        val farsiPassword = "سلام".repeat(18)
        val request = RegisterRequest(email = email, password = farsiPassword, fullName = "Farsi Too Long", role = UserRole.CUSTOMER)

        val response = restTemplate.postForEntity(url("/api/v1/auth/register"), request, String::class.java)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        // Jakarta Validation's {max} message placeholder must actually
        // interpolate to the real limit, not leak the raw template -
        // an API caller needs a readable reason, not "{max}".
        assertTrue(response.body?.contains("72") == true, "expected the real limit (72) in the error body, got: ${response.body}")
        assertFalse(response.body?.contains("{max}") == true, "the {max} message placeholder was not interpolated: ${response.body}")
    }

    @Test
    fun `registers and logs in with a Farsi password that is exactly 72 UTF-8 bytes`() {
        val email = "farsiok.${System.nanoTime()}@example.com"
        // 36 chars * 2 bytes = exactly 72 bytes.
        val farsiPassword = "سلام".repeat(9)

        val register = restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = farsiPassword, fullName = "Farsi OK", role = UserRole.CUSTOMER),
            UserResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, register.statusCode)

        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = farsiPassword),
            AuthResponse::class.java,
        )
        assertEquals(HttpStatus.OK, login.statusCode)
    }

    // LoginRequest's own password field carries no @Utf8ByteSize (login only
    // ever compares against a stored hash, it never calls encode()) - this
    // proves an attacker sending a deliberately over-length login password
    // still fails safely (400, GlobalExceptionHandler's IllegalArgumentException
    // handler) rather than an unhandled 500 from BCryptPasswordEncoder.matches()
    // itself throwing.
    @Test
    fun `login with a deliberately over-length password fails safely, not with a server error`() {
        val email = "overlengthlogin.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "a-real-password", fullName = "Overlength Login", role = UserRole.CUSTOMER),
            UserResponse::class.java,
        )

        val response = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "x".repeat(500)),
            String::class.java,
        )

        assertTrue(response.statusCode.is4xxClientError, "expected a 4xx response, got ${response.statusCode}")
    }

    @Test
    fun `rejects login with the wrong password`() {
        val email = "wrongpass.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "correct-password", fullName = "Wrong Pass", role = UserRole.CUSTOMER),
            UserResponse::class.java,
        )

        val response = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "incorrect-password"),
            String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `rejects a refresh token presented as an access token`() {
        val email = "refreshmisuse.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Refresh Misuse", role = UserRole.CUSTOMER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        val refreshToken = requireNotNull(login.body).refreshToken

        val response = restTemplate.exchange(
            url("/api/v1/users/me"),
            HttpMethod.GET,
            bearer(refreshToken),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `rejects an access token presented as a refresh token`() {
        val email = "accessmisuse.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Access Misuse", role = UserRole.CUSTOMER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        val accessToken = requireNotNull(login.body).accessToken

        val response = restTemplate.postForEntity(
            url("/api/v1/auth/refresh"),
            RefreshRequest(refreshToken = accessToken),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `rejects unauthenticated access to a protected endpoint`() {
        val response = restTemplate.getForEntity(url("/api/v1/users/me"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `unauthenticated access returns the standard AUTH_UNAUTHORIZED contract`() {
        val response = restTemplate.getForEntity(url("/api/v1/users/me"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("""{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}""", response.body)
    }

    @Test
    fun `OpenAPI docs describe the auth and dashboard endpoints`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/auth/register"))
        assertTrue(docs.contains("/api/v1/auth/login"))
        assertTrue(docs.contains("/api/v1/auth/refresh"))
        assertTrue(docs.contains("/api/v1/dashboard/insights"))
    }
}
