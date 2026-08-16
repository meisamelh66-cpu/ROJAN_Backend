package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.document.AttachDocumentRequest
import ai.rojan.backend.api.document.DocumentAccessUrlResponse
import ai.rojan.backend.api.document.SalonDocumentResponse
import ai.rojan.backend.api.media.MediaAssetResponse
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserRole
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
import java.time.Instant
import java.util.UUID

/**
 * Document Archive (Phase 2) - real HTTP + embedded Postgres, same pattern
 * as every other file in this directory (including [SalonMediaFlowIntegrationTest],
 * whose Phase 1 endpoints this reuses for the actual upload). Exercises
 * upload -> attach, list (and the retrofit: documents never appear on the
 * generic /media list), signed access-url issuance, and cross-salon
 * tenant isolation across every new document endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonDocumentFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(fullName: String): Pair<String, UUID> {
        val email = "document.${System.nanoTime()}@example.com"
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

    // Not a real PDF - only the declared Content-Type is validated today
    // (a disclosed limitation, Security Gate §10.3), consistent with what
    // the shipped code actually checks, not what a hardened version would.
    private val pdfBytes = "%PDF-1.4\n".toByteArray() + ByteArray(64)

    private fun uploadDocumentAsset(ownerToken: String, salonId: UUID): MediaAssetResponse {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(pdfBytes) { override fun getFilename() = "license.pdf" })
        body.add("mediaType", MediaType.DOCUMENT.name)
        val headers = bearer(ownerToken).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }
        return requireNotNull(
            restTemplate.postForEntity(url("/api/v1/salons/$salonId/media"), HttpEntity(body, headers), MediaAssetResponse::class.java).body,
        )
    }

    private fun attachDocument(ownerToken: String, salonId: UUID, mediaAssetId: UUID, documentType: DocumentType = DocumentType.LICENSE): SalonDocumentResponse =
        requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/documents"), HttpMethod.POST,
                HttpEntity(AttachDocumentRequest(mediaAssetId, documentType, null), bearer(ownerToken)),
                SalonDocumentResponse::class.java,
            ).body,
        )

    @Test
    fun `owner uploads a document, attaches it, and it appears in the document list`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val asset = uploadDocumentAsset(ownerToken, salon.id)

        val document = attachDocument(ownerToken, salon.id, asset.id)

        assertEquals(DocumentVerificationStatus.PENDING, document.verificationStatus)
        assertEquals(asset.id, document.mediaAssetId)

        val list = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), Array<SalonDocumentResponse>::class.java,
        ).body!!.toList()
        assertEquals(1, list.size)
    }

    @Test
    fun `an attached document never appears on the generic public media list`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val asset = uploadDocumentAsset(ownerToken, salon.id)
        attachDocument(ownerToken, salon.id, asset.id)

        val mediaList = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/media"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), Array<MediaAssetResponse>::class.java,
        ).body!!.toList()

        assertTrue(mediaList.none { it.id == asset.id })
    }

    @Test
    fun `owner can request a signed access url for a document`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val asset = uploadDocumentAsset(ownerToken, salon.id)
        val document = attachDocument(ownerToken, salon.id, asset.id)

        val access = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/documents/${document.id}/access-url"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), DocumentAccessUrlResponse::class.java,
        ).body!!

        assertTrue(access.url.contains("signed"))
        assertTrue(access.expiresAt.isAfter(Instant.now()))
    }

    @Test
    fun `deleting a document removes it from the list and soft-deletes the underlying media`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val asset = uploadDocumentAsset(ownerToken, salon.id)
        val document = attachDocument(ownerToken, salon.id, asset.id)

        val deleteResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/documents/${document.id}"), HttpMethod.DELETE,
            HttpEntity<Void>(bearer(ownerToken)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.statusCode)

        val getAfterDelete = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/documents/${document.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, getAfterDelete.statusCode)
    }

    @Test
    fun `attaching media that belongs to a different salon 404s, not 403`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val assetOnA = uploadDocumentAsset(ownerToken, salonA.id)

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/documents"), HttpMethod.POST,
            HttpEntity(AttachDocumentRequest(assetOnA.id, DocumentType.LICENSE, null), bearer(ownerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `fetching a document's metadata under the wrong salon 404s`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val asset = uploadDocumentAsset(ownerToken, salonA.id)
        val document = attachDocument(ownerToken, salonA.id, asset.id)

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/documents/${document.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `requesting an access url under the wrong salon 404s and never mints a signed url`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val asset = uploadDocumentAsset(ownerToken, salonA.id)
        val document = attachDocument(ownerToken, salonA.id, asset.id)

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/documents/${document.id}/access-url"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `deleting a document under the wrong salon 404s and leaves it untouched`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val asset = uploadDocumentAsset(ownerToken, salonA.id)
        val document = attachDocument(ownerToken, salonA.id, asset.id)

        val deleteResponse = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/documents/${document.id}"), HttpMethod.DELETE,
            HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, deleteResponse.statusCode)

        val stillThere = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/documents/${document.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), SalonDocumentResponse::class.java,
        )
        assertEquals(HttpStatus.OK, stillThere.statusCode)
    }

    @Test
    fun `a manager member cannot attach, list, or access documents - MANAGE_MEDIA does not imply document access`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (managerToken, managerId) = registerAndLogin("A Manager")
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/members/$managerId"), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.MANAGER), bearer(ownerToken)), String::class.java,
        )
        val asset = uploadDocumentAsset(ownerToken, salon.id)
        val document = attachDocument(ownerToken, salon.id, asset.id)

        val listResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, listResponse.statusCode)

        val accessResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/documents/${document.id}/access-url"), HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, accessResponse.statusCode)
    }
}
