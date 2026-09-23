package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.document.SalonDocumentResponse
import ai.rojan.backend.application.document.ApproveSalonDocumentCommand
import ai.rojan.backend.application.document.ApproveSalonDocumentUseCase
import ai.rojan.backend.application.document.ListSalonDocumentsForPlatformQuery
import ai.rojan.backend.application.document.ListSalonDocumentsForPlatformUseCase
import ai.rojan.backend.application.document.RejectSalonDocumentCommand
import ai.rojan.backend.application.document.RejectSalonDocumentUseCase
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform Authority document review (Phase 5) - covers every [ai.rojan.backend.domain.document.DocumentType],
 * `HYGIENE_CERTIFICATE` included (Phase 5 §08: no separate hygiene review mechanism exists, a hygiene
 * certificate is reviewed exactly like any other document). PLATFORM_ADMIN or PLATFORM_REVIEWER only,
 * authorized via [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver] inside
 * each use case - never [ai.rojan.backend.application.salon.SalonPermissionResolver].
 *
 * `salonId` is a path segment for the same tenant-scoping reason documented on
 * [PlatformAuthorityVerificationController] - [ai.rojan.backend.domain.document.SalonDocumentRepository.findByIdAndSalonId]
 * has no bare-id counterpart.
 *
 * API contract-completion phase (post Web Phase 1): [list] closes the one read gap the Certificates
 * workspace found - a platform caller previously had no way to discover which documents (a
 * HYGIENE_CERTIFICATE included) exist for a salon at all, only to act on a `documentId` already
 * known some other way. Reuses [SalonDocumentResponse] (the exact same manager-facing DTO) and the
 * existing [ai.rojan.backend.domain.document.SalonDocumentRepository.findBySalonId] query - no new
 * repository method, no raw storage/media internals exposed.
 */
@RestController
@RequestMapping("/api/v1/platform-authority/salons/{salonId}/documents")
@Tag(name = "Platform Authority - Documents")
class PlatformAuthorityDocumentController(
    private val approveSalonDocumentUseCase: ApproveSalonDocumentUseCase,
    private val rejectSalonDocumentUseCase: RejectSalonDocumentUseCase,
    private val listSalonDocumentsForPlatformUseCase: ListSalonDocumentsForPlatformUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "List a salon's documents, optionally filtered - HYGIENE_CERTIFICATE included (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(required = false) documentType: DocumentType?,
        @RequestParam(required = false) verificationStatus: DocumentVerificationStatus?,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<SalonDocumentResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listSalonDocumentsForPlatformUseCase.execute(
            ListSalonDocumentsForPlatformQuery(SalonId(salonId), callerId, documentType, verificationStatus),
        ).map { it.toResponse() }
    }

    @PostMapping("/{documentId}/approve")
    @Operation(summary = "Approve a single document, individually - never touches any other document or the salon's verification case (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun approve(
        @PathVariable salonId: UUID,
        @PathVariable documentId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonDocumentResponse {
        val callerId = currentUserResolver.resolve(principal)
        return approveSalonDocumentUseCase.execute(
            ApproveSalonDocumentCommand(SalonId(salonId), SalonDocumentId(documentId), callerId),
        ).toResponse()
    }

    @PostMapping("/{documentId}/reject")
    @Operation(summary = "Reject a single document, individually - never deactivates the salon (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun reject(
        @PathVariable salonId: UUID,
        @PathVariable documentId: UUID,
        @Valid @RequestBody request: RejectSalonDocumentRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonDocumentResponse {
        val callerId = currentUserResolver.resolve(principal)
        return rejectSalonDocumentUseCase.execute(
            RejectSalonDocumentCommand(SalonId(salonId), SalonDocumentId(documentId), callerId, request.reason),
        ).toResponse()
    }

    private fun SalonDocument.toResponse() = SalonDocumentResponse(
        id = id.value,
        salonId = salonId.value,
        mediaAssetId = mediaAssetId.value,
        documentType = documentType,
        verificationStatus = verificationStatus,
        expiryDate = expiryDate,
        uploadedBy = uploadedBy.value,
        specialistId = specialistId?.value,
        reviewedBy = reviewedBy?.value,
        reviewedAt = reviewedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
