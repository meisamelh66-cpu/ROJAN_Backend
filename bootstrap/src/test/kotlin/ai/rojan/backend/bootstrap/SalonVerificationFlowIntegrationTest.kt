package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.document.AttachDocumentRequest
import ai.rojan.backend.api.document.SalonDocumentResponse
import ai.rojan.backend.api.media.MediaAssetResponse
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.verification.SalonVerificationResponse
import ai.rojan.backend.api.verification.SubmitVerificationRequest
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserRole
import ai.rojan.backend.domain.verification.SalonVerificationStatus
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType as HttpMediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID

/**
 * Salon Verification Foundation (Phase 3) - real HTTP + embedded
 * Postgres, same pattern as [SalonDocumentFlowIntegrationTest], whose
 * Document Archive endpoints this reuses to get a real, attached document
 * to submit. Submit/Get/History only - review, approve, and reject are
 * not built this phase (no Platform Authority caller exists), so nothing
 * here exercises them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonVerificationFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(fullName: String): Pair<String, UUID> {
        val email = "verification.${System.nanoTime()}@example.com"
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

    private fun createSalon(ownerToken: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    // Not a real PDF - only the declared Content-Type is validated today, same disclosed limitation as SalonDocumentFlowIntegrationTest.
    private val pdfBytes = "%PDF-1.4\n".toByteArray() + ByteArray(64)

    private fun attachedDocument(ownerToken: String, salonId: UUID): SalonDocumentResponse {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(pdfBytes) { override fun getFilename() = "license.pdf" })
        body.add("mediaType", MediaType.DOCUMENT.name)
        val headers = bearer(ownerToken).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }
        val asset = requireNotNull(
            restTemplate.postForEntity(url("/api/v1/salons/$salonId/media"), HttpEntity(body, headers), MediaAssetResponse::class.java).body,
        )
        return requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/documents"), HttpMethod.POST,
                HttpEntity(AttachDocumentRequest(asset.id, DocumentType.LICENSE, null), bearer(ownerToken)),
                SalonDocumentResponse::class.java,
            ).body,
        )
    }

    private fun submit(ownerToken: String, salonId: UUID, documentIds: List<UUID>) =
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/verification"), HttpMethod.POST,
            HttpEntity(SubmitVerificationRequest(documentIds), bearer(ownerToken)),
            SalonVerificationResponse::class.java,
        )

    /** For calls expected to fail - the error body is an `ApiError`, not a `SalonVerificationResponse`, so it must be read as a plain String rather than deserialized into the success DTO. */
    private fun submitExpectingFailure(ownerToken: String, salonId: UUID, documentIds: List<UUID>) =
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/verification"), HttpMethod.POST,
            HttpEntity(SubmitVerificationRequest(documentIds), bearer(ownerToken)),
            String::class.java,
        )

    @Test
    fun `owner submits a verification and can read it back as the current status`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val document = attachedDocument(ownerToken, salon.id)

        val submitResponse = submit(ownerToken, salon.id, listOf(document.id))
        assertEquals(HttpStatus.CREATED, submitResponse.statusCode)
        val submitted = requireNotNull(submitResponse.body)
        assertEquals(SalonVerificationStatus.PENDING, submitted.status)
        assertEquals(listOf(document.id), submitted.documentIds)

        val current = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/verification"), HttpMethod.GET,
                HttpEntity<Void>(bearer(ownerToken)), SalonVerificationResponse::class.java,
            ).body,
        )
        assertEquals(submitted.id, current.id)
        assertEquals(SalonVerificationStatus.PENDING, current.status)
    }

    @Test
    fun `submitting with a document from a different salon is rejected`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val documentOnA = attachedDocument(ownerToken, salonA.id)

        val response = submitExpectingFailure(ownerToken, salonB.id, listOf(documentOnA.id))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `a second submission while one is already active is rejected`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val document = attachedDocument(ownerToken, salon.id)
        submit(ownerToken, salon.id, listOf(document.id))

        val secondDocument = attachedDocument(ownerToken, salon.id)
        val response = submitExpectingFailure(ownerToken, salon.id, listOf(secondDocument.id))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `getting status for a salon with no submission ever made 404s, never leaking a sibling salon's case`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val document = attachedDocument(ownerToken, salonA.id)
        submit(ownerToken, salonA.id, listOf(document.id))

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/verification"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `owner sees submitted case in history`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val document = attachedDocument(ownerToken, salon.id)
        submit(ownerToken, salon.id, listOf(document.id))

        val history = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/verification/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), Array<SalonVerificationResponse>::class.java,
        ).body!!.toList()

        assertEquals(1, history.size)
        assertTrue(history.single().documentIds.contains(document.id))
    }

    @Test
    fun `a manager member cannot submit, get status, or list history - submission is owner-identity only`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (managerToken, managerId) = registerAndLogin("A Manager")
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/members/$managerId"), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.MANAGER), bearer(ownerToken)), String::class.java,
        )
        val document = attachedDocument(ownerToken, salon.id)

        val submitResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/verification"), HttpMethod.POST,
            HttpEntity(SubmitVerificationRequest(listOf(document.id)), bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, submitResponse.statusCode)

        submit(ownerToken, salon.id, listOf(document.id))

        val getResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/verification"), HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, getResponse.statusCode)

        val historyResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/verification/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, historyResponse.statusCode)
    }
}
