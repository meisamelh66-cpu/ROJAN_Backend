package ai.rojan.backend.application.verification

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.verification.SalonGeoClassificationReview
import ai.rojan.backend.domain.verification.SalonGeoClassificationReviewId
import ai.rojan.backend.domain.verification.SalonGeoClassificationReviewRepository
import ai.rojan.backend.domain.verification.SalonVerification
import ai.rojan.backend.domain.verification.SalonVerificationDocumentRepository
import ai.rojan.backend.domain.verification.SalonVerificationId
import ai.rojan.backend.domain.verification.SalonVerificationRepository
import ai.rojan.backend.domain.verification.SalonVerificationStatus

private val OPEN_STATUSES = setOf(SalonVerificationStatus.PENDING, SalonVerificationStatus.UNDER_REVIEW)

/** Mirrors [ai.rojan.backend.application.document.InMemorySalonDocumentRepository]'s style. */
internal class InMemorySalonVerificationRepository : SalonVerificationRepository {
    private val store = mutableMapOf<SalonVerificationId, SalonVerification>()

    override fun save(verification: SalonVerification): SalonVerification = verification.also { store[it.id] = it }

    override fun findByIdAndSalonId(id: SalonVerificationId, salonId: SalonId): SalonVerification? =
        store[id]?.takeIf { it.salonId == salonId }

    override fun findCurrentBySalonId(salonId: SalonId): SalonVerification? =
        store.values.find { it.salonId == salonId && it.status in OPEN_STATUSES }

    override fun findHistoryBySalonId(salonId: SalonId): List<SalonVerification> =
        store.values.filter { it.salonId == salonId }.sortedByDescending { it.createdAt }

    override fun findAllOpen(pageRequest: PageRequest): PageResult<SalonVerification> {
        val filtered = store.values.filter { it.status in OPEN_STATUSES }.sortedByDescending { it.createdAt }
        val fromIndex = (pageRequest.page * pageRequest.size).coerceAtMost(filtered.size)
        val toIndex = (fromIndex + pageRequest.size).coerceAtMost(filtered.size)
        return PageResult(
            content = filtered.subList(fromIndex, toIndex),
            page = pageRequest.page,
            size = pageRequest.size,
            totalElements = filtered.size.toLong(),
        )
    }
}

internal class InMemorySalonVerificationDocumentRepository : SalonVerificationDocumentRepository {
    private val store = mutableMapOf<SalonVerificationId, List<SalonDocumentId>>()

    override fun saveAll(verificationId: SalonVerificationId, documentIds: List<SalonDocumentId>) {
        store[verificationId] = documentIds
    }

    override fun findDocumentIdsByVerificationId(verificationId: SalonVerificationId): List<SalonDocumentId> =
        store[verificationId].orEmpty()
}

internal class InMemorySalonGeoClassificationReviewRepository : SalonGeoClassificationReviewRepository {
    private val store = mutableMapOf<SalonGeoClassificationReviewId, SalonGeoClassificationReview>()

    override fun save(review: SalonGeoClassificationReview): SalonGeoClassificationReview = review.also { store[it.id] = it }

    override fun findBySalonId(salonId: SalonId): List<SalonGeoClassificationReview> =
        store.values.filter { it.salonId == salonId }

    override fun findByVerificationId(verificationId: SalonVerificationId): SalonGeoClassificationReview? =
        store.values.find { it.verificationId == verificationId }
}
