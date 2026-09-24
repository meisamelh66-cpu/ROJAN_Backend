package ai.rojan.backend.api.document

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.document.AttachDocumentCommand
import ai.rojan.backend.application.document.AttachDocumentUseCase
import ai.rojan.backend.application.document.DeleteDocumentCommand
import ai.rojan.backend.application.document.DeleteDocumentUseCase
import ai.rojan.backend.application.document.GetDocumentAccessUrlUseCase
import ai.rojan.backend.application.document.GetDocumentUseCase
import ai.rojan.backend.application.document.ListDocumentsQuery
import ai.rojan.backend.application.document.ListDocumentsUseCase
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Document Archive (Phase 2). Attach only - the raw file already went
 * through the existing `POST /salons/{salonId}/media?mediaType=DOCUMENT`
 * (Phase 1, unchanged). Every read endpoint here requires
 * `VIEW_DOCUMENTS`/`MANAGE_DOCUMENTS` - unlike the generic media list,
 * which is intentionally open (`MediaController.list()`), documents are
 * private by construction (Security Gate §10.1).
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/documents")
@Tag(name = "Documents")
class DocumentController(
    private val attachDocumentUseCase: AttachDocumentUseCase,
    private val listDocumentsUseCase: ListDocumentsUseCase,
    private val getDocumentUseCase: GetDocumentUseCase,
    private val getDocumentAccessUrlUseCase: GetDocumentAccessUrlUseCase,
    private val deleteDocumentUseCase: DeleteDocumentUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach document metadata to an already-uploaded DOCUMENT-type media asset (MANAGE_DOCUMENTS)")
    fun attach(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: AttachDocumentRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonDocumentResponse {
        val callerId = currentUserResolver.resolve(principal)
        val document = attachDocumentUseCase.execute(
            AttachDocumentCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                mediaAssetId = MediaAssetId(request.mediaAssetId),
                documentType = request.documentType,
                expiryDate = request.expiryDate,
            ),
        )
        return document.toResponse()
    }

    @GetMapping
    @Operation(summary = "List a salon's documents, optionally filtered (VIEW_DOCUMENTS or MANAGE_DOCUMENTS)")
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(required = false) documentType: DocumentType?,
        @RequestParam(required = false) verificationStatus: DocumentVerificationStatus?,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<SalonDocumentResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listDocumentsUseCase.execute(ListDocumentsQuery(SalonId(salonId), callerId, documentType, verificationStatus))
            .map { it.toResponse() }
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "Get one document's metadata - never its content (VIEW_DOCUMENTS or MANAGE_DOCUMENTS)")
    fun get(
        @PathVariable salonId: UUID,
        @PathVariable documentId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonDocumentResponse {
        val callerId = currentUserResolver.resolve(principal)
        return getDocumentUseCase.execute(SalonId(salonId), callerId, SalonDocumentId(documentId)).toResponse()
    }

    @GetMapping("/{documentId}/access-url")
    @Operation(summary = "Issue a short-lived signed URL for this document's content - the only way it's ever fetched (VIEW_DOCUMENTS or MANAGE_DOCUMENTS)")
    fun accessUrl(
        @PathVariable salonId: UUID,
        @PathVariable documentId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): DocumentAccessUrlResponse {
        val callerId = currentUserResolver.resolve(principal)
        val result = getDocumentAccessUrlUseCase.execute(SalonId(salonId), callerId, SalonDocumentId(documentId))
        return DocumentAccessUrlResponse(result.url, result.expiresAt)
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a document - soft-deletes the underlying file and removes this record (MANAGE_DOCUMENTS)")
    fun delete(
        @PathVariable salonId: UUID,
        @PathVariable documentId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        deleteDocumentUseCase.execute(DeleteDocumentCommand(SalonId(salonId), callerId, SalonDocumentId(documentId)))
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
