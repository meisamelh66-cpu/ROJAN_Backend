package ai.rojan.backend.application.verification

import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.verification.SalonVerification
import ai.rojan.backend.domain.verification.SalonVerificationDocumentRepository
import ai.rojan.backend.domain.verification.SalonVerificationId
import ai.rojan.backend.domain.verification.SalonVerificationRepository
import ai.rojan.backend.domain.verification.SalonVerificationStatus

/** Mirrors [ai.rojan.backend.application.document.InMemorySalonDocumentRepository]'s style. */
internal class InMemorySalonVerificationRepository : SalonVerificationRepository {
    private val store = mutableMapOf<SalonVerificationId, SalonVerification>()

    override fun save(verification: SalonVerification): SalonVerification = verification.also { store[it.id] = it }

    override fun findByIdAndSalonId(id: SalonVerificationId, salonId: SalonId): SalonVerification? =
        store[id]?.takeIf { it.salonId == salonId }

    override fun findCurrentBySalonId(salonId: SalonId): SalonVerification? =
        store.values.find { it.salonId == salonId && it.status in setOf(SalonVerificationStatus.PENDING, SalonVerificationStatus.UNDER_REVIEW) }

    override fun findHistoryBySalonId(salonId: SalonId): List<SalonVerification> =
        store.values.filter { it.salonId == salonId }.sortedByDescending { it.createdAt }
}

internal class InMemorySalonVerificationDocumentRepository : SalonVerificationDocumentRepository {
    private val store = mutableMapOf<SalonVerificationId, List<SalonDocumentId>>()

    override fun saveAll(verificationId: SalonVerificationId, documentIds: List<SalonDocumentId>) {
        store[verificationId] = documentIds
    }

    override fun findDocumentIdsByVerificationId(verificationId: SalonVerificationId): List<SalonDocumentId> =
        store[verificationId].orEmpty()
}
