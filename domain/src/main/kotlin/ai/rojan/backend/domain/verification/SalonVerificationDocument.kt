package ai.rojan.backend.domain.verification

import ai.rojan.backend.domain.document.SalonDocumentId
import java.time.Instant
import java.util.UUID

/**
 * One document included in a [SalonVerification] submission, recorded once
 * at submit time as an immutable snapshot - never updated, and never
 * deleted independently of its parent case. No behavior of its own, unlike
 * [SalonVerification] itself: there is no invariant here beyond "these
 * values exist together". A single [SalonDocumentId] may appear in more
 * than one row over time - a document rejected as part of one case can be
 * reused, unchanged, in a later resubmission.
 */
data class SalonVerificationDocument(
    val id: UUID,
    val verificationId: SalonVerificationId,
    val documentId: SalonDocumentId,
    val attachedAt: Instant,
) {
    companion object {
        fun create(verificationId: SalonVerificationId, documentId: SalonDocumentId): SalonVerificationDocument =
            SalonVerificationDocument(UUID.randomUUID(), verificationId, documentId, Instant.now())
    }
}
