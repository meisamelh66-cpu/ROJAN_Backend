package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.document.AttachDocumentRequest
import ai.rojan.backend.api.document.SalonDocumentResponse
import ai.rojan.backend.api.media.MediaAssetResponse
import ai.rojan.backend.api.platformauthority.ApproveSalonVerificationRequest
import ai.rojan.backend.api.platformauthority.CreatePlatformReviewerRequest
import ai.rojan.backend.api.platformauthority.GeoClassificationResponse
import ai.rojan.backend.api.platformauthority.PagedVerificationResponse
import ai.rojan.backend.api.platformauthority.PlatformReviewerResponse
import ai.rojan.backend.api.platformauthority.PlatformVerificationResponse
import ai.rojan.backend.api.platformauthority.RejectSalonDocumentRequest
import ai.rojan.backend.api.platformauthority.RejectSalonVerificationRequest
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonCompletenessResponse
import ai.rojan.backend.api.salon.SalonMembershipResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.UpdateSalonCompletionProfileRequest
import ai.rojan.backend.api.verification.SalonVerificationResponse
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import ai.rojan.backend.domain.verification.SalonVerificationStatus
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * Platform Authority API (Phase 5) - real HTTP + embedded Postgres, same pattern as every other file
 * in this directory. Covers Salon Completeness, Platform Authority verification review, platform
 * document review, and reviewer management, end to end. There is no HTTP path to create the very
 * first [UserRole.PLATFORM_ADMIN] (by design - see [PlatformAuthorityReviewerController]'s own doc
 * comment: platform roles are never self-service) so [seedPlatformAdmin] seeds one directly via
 * [userRepository], exactly the way a real deployment would need a one-time bootstrap seed; every
 * [UserRole.PLATFORM_REVIEWER] used below is instead created through the real API by that seeded
 * admin, never seeded directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PlatformAuthorityFlowIntegrationTest {

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

    /** Seeds a real, active PLATFORM_ADMIN directly - see this class's own doc comment for why. */
    private fun seedPlatformAdmin(fullName: String): Pair<String, String> {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), fullName, UserRole.PLATFORM_ADMIN))
        return loginViaOtp(phone) to phone
    }

    private fun registerAndLoginManager(fullName: String): Pair<String, UUID> {
        val email = "platform.${System.nanoTime()}@example.com"
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
            url("/api/v1/salons"), HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    private fun getSalon(token: String, salonId: UUID): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId"), HttpMethod.GET, HttpEntity<Void>(bearer(token)), SalonResponse::class.java,
        ).body,
    )

    private fun assignMembership(ownerToken: String, salonId: UUID, userId: UUID, role: SalonRole): SalonMembershipResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/members/$userId"), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(role), bearer(ownerToken)), SalonMembershipResponse::class.java,
        ).body,
    )

    private val pdfBytes = "%PDF-1.4\n".toByteArray() + ByteArray(64)

    private fun attachedDocument(ownerToken: String, salonId: UUID): SalonDocumentResponse {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(pdfBytes) { override fun getFilename() = "hygiene.pdf" })
        body.add("mediaType", MediaType.DOCUMENT.name)
        val headers = bearer(ownerToken).apply { contentType = HttpMediaType.MULTIPART_FORM_DATA }
        val asset = requireNotNull(
            restTemplate.postForEntity(url("/api/v1/salons/$salonId/media"), HttpEntity(body, headers), MediaAssetResponse::class.java).body,
        )
        return requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/documents"), HttpMethod.POST,
                HttpEntity(AttachDocumentRequest(asset.id, DocumentType.HYGIENE_CERTIFICATE, null), bearer(ownerToken)),
                SalonDocumentResponse::class.java,
            ).body,
        )
    }

    private fun initiate(adminToken: String, salonId: UUID): PlatformVerificationResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/$salonId/verifications/initiate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), PlatformVerificationResponse::class.java,
        ).body,
    )

    private fun startReview(adminToken: String, salonId: UUID, verificationId: UUID): PlatformVerificationResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/$salonId/verifications/$verificationId/start-review"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), PlatformVerificationResponse::class.java,
        ).body,
    )

    @Test
    fun `manager can view and update salon completeness for an owned salon`() {
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val (_, contactUserId) = registerAndLoginManager("Reza Contact")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val initial = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.GET,
                HttpEntity<Void>(bearer(ownerToken)), SalonCompletenessResponse::class.java,
            ).body,
        )
        assertTrue(initial.missingForActivation.contains("at least one active service"))
        // Salon Completeness fields are trackable but never activation-blocking - a real Phase 4
        // regression, reverted before Phase 5 shipped. See ActivateSalonUseCase's own doc comment.
        assertFalse(initial.missingForActivation.contains("activity start year"))
        assertFalse(initial.missingForActivation.contains("a designated primary contact"))

        val membership = assignMembership(ownerToken, salon.id, contactUserId, SalonRole.MANAGER)

        val updated = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.PUT,
                HttpEntity(
                    ai.rojan.backend.api.salon.UpdateSalonCompletionProfileRequest(
                        activityStartJalaliYear = 1399,
                        hasInternalExtensions = true,
                        sellsProducts = true,
                        hasCafe = false,
                        hasStaffUniform = true,
                        isNeighborhoodSalon = true,
                        isCityCenterSalon = false,
                        primaryContactMembershipId = membership.id,
                    ),
                    bearer(ownerToken),
                ),
                SalonCompletenessResponse::class.java,
            ).body,
        )
        assertEquals(1399, updated.activityStartJalaliYear)
        assertEquals(membership.id, updated.primaryContactMembershipId)
        assertFalse(updated.missingForActivation.contains("activity start year"))
        assertFalse(updated.missingForActivation.contains("a designated primary contact"))
    }

    @Test
    fun `a non-member cannot access another owner's salon completeness`() {
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (strangerToken, _) = registerAndLoginManager("Not A Member")

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.GET,
            HttpEntity<Void>(bearer(strangerToken)), String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `rojanVerified cannot be set through the completeness update endpoint even via raw JSON injection`() {
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        assertFalse(salon.rojanVerified)

        val headers = bearer(ownerToken).apply { contentType = HttpMediaType.APPLICATION_JSON }
        val rawBody = """{"activityStartJalaliYear":1399,"hasInternalExtensions":false,"rojanVerified":true,"rojanVerifiedAt":"2020-01-01T00:00:00Z"}"""
        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.PUT,
            HttpEntity(rawBody, headers), SalonCompletenessResponse::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)

        val reloaded = getSalon(ownerToken, salon.id)
        assertFalse(reloaded.rojanVerified, "rojanVerified must stay a system-controlled projection, never client-writable")
    }

    @Test
    fun `activation succeeds and never requires or exposes a rojanVerified precondition`() {
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (_, contactUserId) = registerAndLoginManager("Reza Contact")
        val membership = assignMembership(ownerToken, salon.id, contactUserId, SalonRole.MANAGER)

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.PUT,
            HttpEntity(
                ai.rojan.backend.api.salon.UpdateSalonCompletionProfileRequest(
                    activityStartJalaliYear = 1399,
                    primaryContactMembershipId = membership.id,
                ),
                bearer(ownerToken),
            ),
            SalonCompletenessResponse::class.java,
        )

        val hair = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(ai.rojan.backend.api.salon.CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
            ai.rojan.backend.api.salon.ServiceCategoryResponse::class.java,
        ).body!!
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${hair.id}/services"), HttpMethod.POST,
            HttpEntity(
                ai.rojan.backend.api.salon.CreateServiceRequest("Haircut", null, 30, java.math.BigDecimal("25.00")),
                bearer(ownerToken),
            ),
            ai.rojan.backend.api.salon.ServiceResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"), HttpMethod.POST,
            HttpEntity(
                ai.rojan.backend.api.salon.CreateSpecialistRequest(null, "Mariam Karimi", null, null, "+989120000001", "Stylist"),
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

        val activation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )

        assertEquals(HttpStatus.OK, activation.statusCode)
        assertEquals(ai.rojan.backend.domain.salon.SalonOnboardingStatus.ACTIVE, activation.body!!.onboardingStatus)
        assertFalse(activation.body!!.rojanVerified, "activation must never imply or require ROJAN verification")
    }

    @Test
    fun `PLATFORM_ADMIN can list, start-review, approve and later reject verification cases, and the projection follows`() {
        // Two distinct admins - SalonVerification.startReview()'s own self-review guard
        // (reviewerId != submittedBy, discovered in Phase 4) forbids the same platform account from
        // both initiating and reviewing its own case.
        val (initiatorToken, _) = seedPlatformAdmin("Admin One")
        val (adminToken, _) = seedPlatformAdmin("Admin One B")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val opened = initiate(initiatorToken, salon.id)
        assertEquals(SalonVerificationStatus.PENDING, opened.status)

        val pending = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/verifications"), HttpMethod.GET,
                HttpEntity<Void>(bearer(adminToken)), PagedVerificationResponse::class.java,
            ).body,
        )
        assertTrue(pending.content.any { it.id == opened.id })

        val underReview = startReview(adminToken, salon.id, opened.id)
        assertEquals(SalonVerificationStatus.UNDER_REVIEW, underReview.status)

        val approved = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}/approve"), HttpMethod.POST,
                HttpEntity(ApproveSalonVerificationRequest(qualityScore = 4, decorScore = 5), bearer(adminToken)),
                PlatformVerificationResponse::class.java,
            ).body,
        )
        assertEquals(SalonVerificationStatus.APPROVED, approved.status)
        assertEquals(4, approved.qualityScore)

        val afterApprove = getSalon(ownerToken, salon.id)
        assertTrue(afterApprove.rojanVerified, "approving the salon's latest concluded case must flip the projection true")
        assertNotNull(afterApprove.rojanVerifiedAt)

        val secondCase = initiate(initiatorToken, salon.id)
        startReview(adminToken, salon.id, secondCase.id)
        val rejected = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/salons/${salon.id}/verifications/${secondCase.id}/reject"), HttpMethod.POST,
                HttpEntity(RejectSalonVerificationRequest("Hygiene concerns found on re-review"), bearer(adminToken)),
                PlatformVerificationResponse::class.java,
            ).body,
        )
        assertEquals(SalonVerificationStatus.REJECTED, rejected.status)

        val afterReject = getSalon(ownerToken, salon.id)
        assertFalse(afterReject.rojanVerified, "a later rejection must clear the projection, and never deactivate the salon")

        val history = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/verification/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), Array<ai.rojan.backend.api.verification.SalonVerificationResponse>::class.java,
        ).body!!.toList()
        assertEquals(2, history.size, "manager-visible history must preserve every case, never overwrite one with the next")
    }

    @Test
    fun `manager-submitted verification with a real attached document requires document approval, then flips rojanVerified, independent of activation`() {
        // ROJAN Verification End-to-End Contract Validation phase: the one lifecycle gap every prior
        // approve/reject test above left unproven - they all open their case via the reviewer-initiated
        // `initiate` endpoint, which links zero documents, so `SalonVerification.approve`'s own
        // `allDocumentsApproved` domain guard was never exercised against a real, non-empty,
        // not-yet-approved document list. This test drives the actual Android Manager path instead:
        // real media upload -> real document attach -> real documentId -> real
        // `POST .../verification` submission - mirroring `PlatformAuthorityFlowIntegrationTest`'s own
        // [attachedDocument] helper, which is the same upload+attach sequence
        // `ManagerVerificationViewModel.uploadAndAttachDocument` performs on Android.
        val (adminToken, _) = seedPlatformAdmin("Admin Seven")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        // Activation readiness is set up and the salon is activated BEFORE any verification case
        // exists at all, to prove activation and verification are fully independent lifecycles - not
        // just that a later rejection doesn't deactivate (already covered above), but that activation
        // never depends on verification in the first place.
        val hair = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(ai.rojan.backend.api.salon.CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
            ai.rojan.backend.api.salon.ServiceCategoryResponse::class.java,
        ).body!!
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${hair.id}/services"), HttpMethod.POST,
            HttpEntity(
                ai.rojan.backend.api.salon.CreateServiceRequest("Haircut", null, 30, java.math.BigDecimal("25.00")),
                bearer(ownerToken),
            ),
            ai.rojan.backend.api.salon.ServiceResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"), HttpMethod.POST,
            HttpEntity(
                ai.rojan.backend.api.salon.CreateSpecialistRequest(null, "Mariam Karimi", null, null, "+989120000002", "Stylist"),
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
        val activation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, activation.statusCode)
        assertEquals(ai.rojan.backend.domain.salon.SalonOnboardingStatus.ACTIVE, activation.body!!.onboardingStatus)

        val document = attachedDocument(ownerToken, salon.id)
        val submitted = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/verification"), HttpMethod.POST,
                HttpEntity(ai.rojan.backend.api.verification.SubmitVerificationRequest(listOf(document.id)), bearer(ownerToken)),
                SalonVerificationResponse::class.java,
            ).body,
        )
        assertEquals(SalonVerificationStatus.PENDING, submitted.status)
        assertEquals(listOf(document.id), submitted.documentIds)

        startReview(adminToken, salon.id, submitted.id)

        // The real domain guard, not a caller-supplied flag: approving before the one linked document
        // is itself individually approved must fail.
        val prematureApprove = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${submitted.id}/approve"), HttpMethod.POST,
            HttpEntity(ApproveSalonVerificationRequest(qualityScore = 5, decorScore = 5), bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, prematureApprove.statusCode)

        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents/${document.id}/approve"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), SalonDocumentResponse::class.java,
        )

        val approved = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/salons/${salon.id}/verifications/${submitted.id}/approve"), HttpMethod.POST,
                HttpEntity(ApproveSalonVerificationRequest(qualityScore = 5, decorScore = 5), bearer(adminToken)),
                PlatformVerificationResponse::class.java,
            ).body,
        )
        assertEquals(SalonVerificationStatus.APPROVED, approved.status)

        val afterApprove = getSalon(ownerToken, salon.id)
        assertTrue(afterApprove.rojanVerified, "the manager-submitted, document-backed case's approval must flip the projection true")
        assertNotNull(afterApprove.rojanVerifiedAt)
        assertEquals(
            ai.rojan.backend.domain.salon.SalonOnboardingStatus.ACTIVE,
            afterApprove.onboardingStatus,
            "activation must remain unaffected by verification approval - it was already ACTIVE before any case existed",
        )

        // Android Manager reads the resulting state exactly as GET .../verification/history already
        // returns it - GET .../verification itself only ever returns an OPEN case and 404s once this
        // one concluded (same fallback `ManagerVerificationViewModel.load` already relies on).
        val history = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/verification/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)), Array<SalonVerificationResponse>::class.java,
        ).body!!.toList()
        assertEquals(1, history.size)
        assertEquals(SalonVerificationStatus.APPROVED, history.single().status)
        assertEquals(listOf(document.id), history.single().documentIds)
    }

    @Test
    fun `PLATFORM_REVIEWER created by an admin can list, start-review, approve and reject verification cases`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Two")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val created = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/reviewers"), HttpMethod.POST,
                HttpEntity(CreatePlatformReviewerRequest(randomPhone(), "Reviewer One"), bearer(adminToken)),
                PlatformReviewerResponse::class.java,
            ).body,
        )
        val reviewerToken = loginViaOtp(requireNotNull(created.phoneNumber))

        // adminToken initiates, reviewerToken reviews - distinct identities, same self-review guard
        // reasoning as the PLATFORM_ADMIN test above.
        val opened = initiate(adminToken, salon.id)
        startReview(reviewerToken, salon.id, opened.id)
        val approved = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}/approve"), HttpMethod.POST,
            HttpEntity(ApproveSalonVerificationRequest(), bearer(reviewerToken)), PlatformVerificationResponse::class.java,
        )
        assertEquals(HttpStatus.OK, approved.statusCode)
        assertEquals(SalonVerificationStatus.APPROVED, approved.body!!.status)
    }

    @Test
    fun `PLATFORM_REVIEWER cannot manage reviewers - only PLATFORM_ADMIN can`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Three")
        val created = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/reviewers"), HttpMethod.POST,
                HttpEntity(CreatePlatformReviewerRequest(randomPhone(), "Reviewer Two"), bearer(adminToken)),
                PlatformReviewerResponse::class.java,
            ).body,
        )
        val reviewerToken = loginViaOtp(requireNotNull(created.phoneNumber))

        val createAttempt = restTemplate.exchange(
            url("/api/v1/platform-authority/reviewers"), HttpMethod.POST,
            HttpEntity(CreatePlatformReviewerRequest(randomPhone(), "Someone Else"), bearer(reviewerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, createAttempt.statusCode)

        val listAttempt = restTemplate.exchange(
            url("/api/v1/platform-authority/reviewers"), HttpMethod.GET, HttpEntity<Void>(bearer(reviewerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, listAttempt.statusCode)

        val deactivateAttempt = restTemplate.exchange(
            url("/api/v1/platform-authority/reviewers/${created.id}/deactivate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(reviewerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, deactivateAttempt.statusCode)
    }

    @Test
    fun `PLATFORM_ADMIN can create, list, deactivate and reactivate a reviewer`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Four")

        val created = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/reviewers"), HttpMethod.POST,
                HttpEntity(CreatePlatformReviewerRequest(randomPhone(), "Reviewer Three"), bearer(adminToken)),
                PlatformReviewerResponse::class.java,
            ).body,
        )
        assertTrue(created.active)

        val list = restTemplate.exchange(
            url("/api/v1/platform-authority/reviewers"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), Array<PlatformReviewerResponse>::class.java,
        ).body!!.toList()
        assertTrue(list.any { it.id == created.id })

        val deactivated = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/reviewers/${created.id}/deactivate"), HttpMethod.POST,
                HttpEntity<Void>(bearer(adminToken)), PlatformReviewerResponse::class.java,
            ).body,
        )
        assertFalse(deactivated.active)

        val reactivated = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/reviewers/${created.id}/reactivate"), HttpMethod.POST,
                HttpEntity<Void>(bearer(adminToken)), PlatformReviewerResponse::class.java,
            ).body,
        )
        assertTrue(reactivated.active)
    }

    @Test
    fun `a CUSTOMER cannot access any platform-authority route`() {
        val phone = randomPhone()
        val customerToken = loginViaOtp(phone)

        val listVerifications = restTemplate.exchange(
            url("/api/v1/platform-authority/verifications"), HttpMethod.GET, HttpEntity<Void>(bearer(customerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, listVerifications.statusCode)

        val listReviewers = restTemplate.exchange(
            url("/api/v1/platform-authority/reviewers"), HttpMethod.GET, HttpEntity<Void>(bearer(customerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, listReviewers.statusCode)
    }

    @Test
    fun `PLATFORM_ADMIN can approve and reject documents individually, including a HYGIENE_CERTIFICATE`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Five")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val toApprove = attachedDocument(ownerToken, salon.id)
        val toReject = attachedDocument(ownerToken, salon.id)

        val approved = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/salons/${salon.id}/documents/${toApprove.id}/approve"), HttpMethod.POST,
                HttpEntity<Void>(bearer(adminToken)), SalonDocumentResponse::class.java,
            ).body,
        )
        assertEquals(ai.rojan.backend.domain.document.DocumentVerificationStatus.APPROVED, approved.verificationStatus)
        assertNotNull(approved.reviewedBy)
        assertNotNull(approved.reviewedAt)
        assertEquals(DocumentType.HYGIENE_CERTIFICATE, approved.documentType)

        val rejected = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/salons/${salon.id}/documents/${toReject.id}/reject"), HttpMethod.POST,
                HttpEntity(RejectSalonDocumentRequest("Illegible scan"), bearer(adminToken)), SalonDocumentResponse::class.java,
            ).body,
        )
        assertEquals(ai.rojan.backend.domain.document.DocumentVerificationStatus.REJECTED, rejected.verificationStatus)
    }

    @Test
    fun `rejecting a verification with a blank reason is rejected as invalid`() {
        val (initiatorToken, _) = seedPlatformAdmin("Admin Six")
        val (adminToken, _) = seedPlatformAdmin("Admin Six B")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val opened = initiate(initiatorToken, salon.id)
        startReview(adminToken, salon.id, opened.id)

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}/reject"), HttpMethod.POST,
            HttpEntity(RejectSalonVerificationRequest(""), bearer(adminToken)), String::class.java,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `self-registering with a platform role through the public register endpoint is rejected`() {
        val response = restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = "wannabe.admin.${System.nanoTime()}@example.com", password = "supersecret123", fullName = "Wannabe Admin", role = UserRole.PLATFORM_ADMIN),
            String::class.java,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    private fun createReviewer(adminToken: String, fullName: String): Pair<String, PlatformReviewerResponse> {
        val created = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/reviewers"), HttpMethod.POST,
                HttpEntity(CreatePlatformReviewerRequest(randomPhone(), fullName), bearer(adminToken)),
                PlatformReviewerResponse::class.java,
            ).body,
        )
        return loginViaOtp(requireNotNull(created.phoneNumber)) to created
    }

    // ---- API contract-completion phase: the four Web Phase 1 read gaps ----

    @Test
    fun `PLATFORM_ADMIN and PLATFORM_REVIEWER can retrieve a single verification case by id, but a non-platform user cannot`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Seven")
        val (reviewerToken, _) = createReviewer(adminToken, "Reviewer Four")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val opened = initiate(adminToken, salon.id)

        val adminGet = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), SalonVerificationResponse::class.java,
        )
        assertEquals(HttpStatus.OK, adminGet.statusCode)
        assertEquals(opened.id, adminGet.body!!.id)
        assertEquals(salon.id, adminGet.body!!.salonId)
        assertEquals(SalonVerificationStatus.PENDING, adminGet.body!!.status)

        val reviewerGet = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(reviewerToken)), SalonVerificationResponse::class.java,
        )
        assertEquals(HttpStatus.OK, reviewerGet.statusCode)

        val customerToken = loginViaOtp(randomPhone())
        val customerGet = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, customerGet.statusCode)
    }

    @Test
    fun `getting a single verification case that does not exist 404s, never leaking a sibling salon's case`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Twelve")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${UUID.randomUUID()}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `both platform roles can list a salon's documents, including HYGIENE_CERTIFICATE - a non-platform caller cannot`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Eight")
        val (reviewerToken, _) = createReviewer(adminToken, "Reviewer Five")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val document = attachedDocument(ownerToken, salon.id)

        val adminList = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), Array<SalonDocumentResponse>::class.java,
        ).body!!.toList()
        assertTrue(adminList.any { it.id == document.id && it.documentType == DocumentType.HYGIENE_CERTIFICATE })

        val reviewerList = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(reviewerToken)), Array<SalonDocumentResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, reviewerList.statusCode)

        val customerToken = loginViaOtp(randomPhone())
        val customerList = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, customerList.statusCode)
    }

    @Test
    fun `an approved document's reviewer identity and review timestamp appear in the platform document list`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Nine")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val document = attachedDocument(ownerToken, salon.id)

        val beforeApproval = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), Array<SalonDocumentResponse>::class.java,
        ).body!!.first { it.id == document.id }
        assertNull(beforeApproval.reviewedBy)
        assertNull(beforeApproval.reviewedAt)

        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents/${document.id}/approve"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), SalonDocumentResponse::class.java,
        )

        val afterApproval = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), Array<SalonDocumentResponse>::class.java,
        ).body!!.first { it.id == document.id }
        assertEquals(DocumentVerificationStatus.APPROVED, afterApproval.verificationStatus)
        assertNotNull(afterApproval.reviewedBy)
        assertNotNull(afterApproval.reviewedAt)
    }

    @Test
    fun `geo classification is returned with declared and verified values kept separate`() {
        val (initiatorToken, _) = seedPlatformAdmin("Admin Ten")
        val (adminToken, _) = seedPlatformAdmin("Admin Ten B")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.PUT,
            HttpEntity(
                UpdateSalonCompletionProfileRequest(isNeighborhoodSalon = true, isCityCenterSalon = false),
                bearer(ownerToken),
            ),
            SalonCompletenessResponse::class.java,
        )
        val opened = initiate(initiatorToken, salon.id)
        startReview(adminToken, salon.id, opened.id)

        val beforeApproval = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}/geo-classification"), HttpMethod.GET,
                HttpEntity<Void>(bearer(adminToken)), GeoClassificationResponse::class.java,
            ).body,
        )
        assertNull(beforeApproval.declaredNeighborhood, "no geo review has been recorded yet - every field must honestly be null, never fabricated")
        assertNull(beforeApproval.verifiedNeighborhood)

        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}/approve"), HttpMethod.POST,
            HttpEntity(ApproveSalonVerificationRequest(verifiedNeighborhood = true, verifiedCityCenter = false), bearer(adminToken)),
            PlatformVerificationResponse::class.java,
        )

        val afterApproval = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}/geo-classification"), HttpMethod.GET,
                HttpEntity<Void>(bearer(adminToken)), GeoClassificationResponse::class.java,
            ).body,
        )
        assertEquals(true, afterApproval.declaredNeighborhood, "the owner's declared value at approval time")
        assertEquals(false, afterApproval.declaredCityCenter)
        assertEquals(true, afterApproval.verifiedNeighborhood, "the reviewer's independent decision - never merged with the declared value")
        assertEquals(false, afterApproval.verifiedCityCenter)
    }

    @Test
    fun `platform roles can retrieve full verification history for a salon, preserving every case`() {
        val (initiatorToken, _) = seedPlatformAdmin("Admin Eleven")
        val (adminToken, _) = seedPlatformAdmin("Admin Eleven B")
        val (reviewerToken, _) = createReviewer(adminToken, "Reviewer Six")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val firstCase = initiate(initiatorToken, salon.id)
        startReview(adminToken, salon.id, firstCase.id)
        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${firstCase.id}/reject"), HttpMethod.POST,
            HttpEntity(RejectSalonVerificationRequest("Missing hygiene certificate"), bearer(adminToken)), PlatformVerificationResponse::class.java,
        )
        val secondCase = initiate(initiatorToken, salon.id)

        val adminHistory = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), Array<SalonVerificationResponse>::class.java,
        ).body!!.toList()
        assertEquals(2, adminHistory.size, "history must preserve every case, never collapse into a single current record")
        assertTrue(adminHistory.any { it.id == firstCase.id && it.status == SalonVerificationStatus.REJECTED })
        assertTrue(adminHistory.any { it.id == secondCase.id && it.status == SalonVerificationStatus.PENDING })

        val reviewerHistory = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(reviewerToken)), Array<SalonVerificationResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, reviewerHistory.statusCode)
        assertEquals(2, reviewerHistory.body!!.size)

        val customerToken = loginViaOtp(randomPhone())
        val customerHistory = restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, customerHistory.statusCode)
    }

    @Test
    fun `the new platform-authority read endpoints never allow rojanVerified to be set - none accept a request body at all`() {
        val (adminToken, _) = seedPlatformAdmin("Admin Thirteen")
        val (ownerToken, _) = registerAndLoginManager("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val opened = initiate(adminToken, salon.id)
        assertFalse(salon.rojanVerified)

        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), SalonVerificationResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/history"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), Array<SalonVerificationResponse>::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/documents"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), Array<SalonDocumentResponse>::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/${salon.id}/verifications/${opened.id}/geo-classification"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), GeoClassificationResponse::class.java,
        )

        val stillUnverified = getSalon(ownerToken, salon.id)
        assertFalse(stillUnverified.rojanVerified, "these read-only endpoints must never change the rojanVerified projection")
    }
}
