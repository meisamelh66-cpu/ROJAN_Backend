package ai.rojan.backend.api.document

import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AttachDocumentRequest(
    @field:NotNull
    val mediaAssetId: UUID,

    @field:NotNull
    val documentType: DocumentType,

    val expiryDate: LocalDate? = null,
)

/**
 * Deliberately no `url`/`accessUrl` field - see `GET /documents/{id}/access-url`. Metadata viewing and
 * content access are separate authorization moments (Security Gate §10.1).
 *
 * [specialistId]/[reviewedBy]/[reviewedAt] (Staff Hygiene Certificates, Phase 5) are additive - `null`
 * for a document that isn't tied to a specialist, or hasn't yet been reviewed. Populated identically
 * whether this document was reached through this salon-scoped controller or the platform-authority
 * review routes - the same [ai.rojan.backend.domain.document.SalonDocument] aggregate either way.
 */
data class SalonDocumentResponse(
    val id: UUID,
    val salonId: UUID,
    val mediaAssetId: UUID,
    val documentType: DocumentType,
    val verificationStatus: DocumentVerificationStatus,
    val expiryDate: LocalDate?,
    val uploadedBy: UUID,
    val specialistId: UUID?,
    val reviewedBy: UUID?,
    val reviewedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DocumentAccessUrlResponse(
    val url: String,
    val expiresAt: Instant,
)
