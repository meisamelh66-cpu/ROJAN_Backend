package ai.rojan.backend.domain.verification

import ai.rojan.backend.domain.document.SalonDocumentId

interface SalonVerificationDocumentRepository {
    /** Writes the full document set for a submission in one call - written once, at submit time, never appended to or edited afterward. */
    fun saveAll(verificationId: SalonVerificationId, documentIds: List<SalonDocumentId>)

    fun findDocumentIdsByVerificationId(verificationId: SalonVerificationId): List<SalonDocumentId>
}
