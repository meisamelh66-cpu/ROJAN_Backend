package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RefreshRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
}
