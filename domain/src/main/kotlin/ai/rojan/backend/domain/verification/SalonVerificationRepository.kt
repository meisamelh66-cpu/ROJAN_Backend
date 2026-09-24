package ai.rojan.backend.domain.verification

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.salon.SalonId

interface SalonVerificationRepository {
    fun save(verification: SalonVerification): SalonVerification

    /** Tenant-scoped by construction, same discipline as [ai.rojan.backend.domain.document.SalonDocumentRepository.findByIdAndSalonId] - a cross-salon reference 404s, it never leaks. */
    fun findByIdAndSalonId(id: SalonVerificationId, salonId: SalonId): SalonVerification?

    /** The one case currently open for a salon, if any - status in PENDING or UNDER_REVIEW. Backs both "is there already an active submission" and "what's the current status". */
    fun findCurrentBySalonId(salonId: SalonId): SalonVerification?

    /** Every case ever submitted for a salon, newest first - the append-only trail a rejection-then-resubmit flow relies on. */
    fun findHistoryBySalonId(salonId: SalonId): List<SalonVerification>

    /**
     * Platform Authority review queue (Phase 4): every case currently open
     * (PENDING or UNDER_REVIEW), across every salon - the one genuinely new
     * repository capability this phase required, since no existing query
     * looks across salons (every other finder here is deliberately
     * salon-scoped). [ListPendingVerificationsUseCase] cannot exist without
     * it.
     */
    fun findAllOpen(pageRequest: PageRequest): PageResult<SalonVerification>
}
