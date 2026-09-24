package ai.rojan.backend.application.verification

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.InvalidVerificationDocumentException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SalonVerificationNotFoundException
import ai.rojan.backend.domain.common.VerificationAlreadyPendingException
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.verification.SalonGeoClassificationReview
import ai.rojan.backend.domain.verification.SalonGeoClassificationReviewRepository
import ai.rojan.backend.domain.verification.SalonVerification
import ai.rojan.backend.domain.verification.SalonVerificationDocumentRepository
import ai.rojan.backend.domain.verification.SalonVerificationId
import ai.rojan.backend.domain.verification.SalonVerificationRepository
import ai.rojan.backend.domain.verification.SalonVerificationStatus

/** A submission's documents, resolved alongside the case itself - [SalonVerification] carries no document-shaped field of its own (see the domain type's own doc comment). */
data class VerificationWithDocuments(
    val verification: SalonVerification,
    val documentIds: List<SalonDocumentId>,
)

data class SubmitVerificationCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val documentIds: List<SalonDocumentId>,
)

/**
 * Submission is an identity check against [ai.rojan.backend.domain.salon.Salon.ownerId],
 * not a permission grant - no staff role, however senior, may submit on
 * the owner's behalf (Phase 3 architecture §04).
 */
class SubmitVerificationUseCase(
    private val salonRepository: SalonRepository,
    private val documentRepository: SalonDocumentRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
) {
    fun execute(command: SubmitVerificationCommand): VerificationWithDocuments {
        val salon = salonRepository.findById(command.salonId) ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (command.callerId != salon.ownerId) {
            throw SalonAccessDeniedException(command.salonId.value.toString())
        }
        require(command.documentIds.isNotEmpty()) { "At least one document is required to submit for verification" }

        if (verificationRepository.findCurrentBySalonId(command.salonId) != null) {
            throw VerificationAlreadyPendingException(command.salonId.value.toString())
        }
        command.documentIds.forEach { documentId ->
            documentRepository.findByIdAndSalonId(documentId, command.salonId)
                ?: throw InvalidVerificationDocumentException(documentId.value.toString())
        }

        val verification = verificationRepository.save(SalonVerification.create(command.salonId, command.callerId))
        verificationDocumentRepository.saveAll(verification.id, command.documentIds)
        return VerificationWithDocuments(verification, command.documentIds)
    }
}

class GetVerificationUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(salonId: SalonId, callerId: UserId): VerificationWithDocuments {
        salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())
        salonPermissionResolver.requireAny(salonId, callerId, Permission.VIEW_DOCUMENTS, Permission.MANAGE_DOCUMENTS)
        val verification = verificationRepository.findCurrentBySalonId(salonId)
            ?: throw SalonVerificationNotFoundException(salonId.value.toString())
        return VerificationWithDocuments(verification, verificationDocumentRepository.findDocumentIdsByVerificationId(verification.id))
    }
}

/** Owner-only, unlike [GetVerificationUseCase] - the full case-by-case trail is a step more sensitive than "what's the current status". */
class ListVerificationHistoryUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
) {
    fun execute(salonId: SalonId, callerId: UserId): List<VerificationWithDocuments> {
        val salon = salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())
        if (callerId != salon.ownerId) {
            throw SalonAccessDeniedException(salonId.value.toString())
        }
        return verificationRepository.findHistoryBySalonId(salonId).map { verification ->
            VerificationWithDocuments(verification, verificationDocumentRepository.findDocumentIdsByVerificationId(verification.id))
        }
    }
}

// ---- Platform Authority review (Phase 4) ----
//
// GetVerificationUseCase/ListVerificationHistoryUseCase above already fully
// cover "the manager's current status" and "the manager's full history" -
// deliberately not duplicated under new names here. The genuinely new
// manager-facing capability this phase adds is InitiateRojanReviewUseCase
// (reviewer/admin-initiated, as opposed to SubmitVerificationUseCase's
// owner-initiated submission).

/**
 * Recomputes and writes [ai.rojan.backend.domain.salon.Salon.rojanVerified]/
 * [ai.rojan.backend.domain.salon.Salon.rojanVerifiedAt] after any
 * approve/reject decision - the approved rule exactly: true only when the
 * salon's *latest concluded* case (APPROVED or REJECTED) is APPROVED. Reuses
 * [SalonVerificationRepository.findHistoryBySalonId] (already newest-created-
 * first) rather than adding a dedicated "latest concluded" query - a new case
 * is essentially always opened after the one before it is resolved, so
 * newest-created and newest-concluded coincide in every realistic sequence,
 * and this avoids a second new repository method beyond the one
 * ([SalonVerificationRepository.findAllOpen]) this phase already needed.
 * Never called for anything except an approve/reject decision - a REJECTED
 * verdict correctly clears the badge without touching [ai.rojan.backend.domain.salon.Salon.active]/
 * [ai.rojan.backend.domain.salon.Salon.onboardingStatus] anywhere in this
 * function.
 */
private fun recomputeRojanVerifiedProjection(
    salon: Salon,
    verificationRepository: SalonVerificationRepository,
    salonRepository: SalonRepository,
) {
    val latestConcluded = verificationRepository.findHistoryBySalonId(salon.id)
        .firstOrNull { it.status == SalonVerificationStatus.APPROVED || it.status == SalonVerificationStatus.REJECTED }
    val verified = latestConcluded?.status == SalonVerificationStatus.APPROVED
    salon.projectRojanVerification(verified, latestConcluded?.reviewedAt)
    salonRepository.save(salon)
}

data class InitiateRojanReviewCommand(val salonId: SalonId, val reviewerId: UserId)

/**
 * Reviewer/admin-initiated periodic re-review - distinct from
 * [SubmitVerificationUseCase] (owner-initiated, requires documents, only ever
 * one at a time via [VerificationAlreadyPendingException]). Reuses the exact
 * same [SalonVerification.create] factory; [SalonVerification.submittedBy]
 * is the reviewer's own id here - an honest "who opened this case" value,
 * not a fabricated owner submission. Not gated on
 * [ai.rojan.backend.domain.salon.Salon.onboardingStatus] - a reviewer may
 * open a case for any existing salon, per the approved design (no such
 * restriction was specified).
 */
class InitiateRojanReviewUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: InitiateRojanReviewCommand): SalonVerification {
        platformAuthorization.requirePlatformReviewerOrAdmin(command.reviewerId)
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())

        if (verificationRepository.findCurrentBySalonId(salon.id) != null) {
            throw VerificationAlreadyPendingException(salon.id.value.toString())
        }

        val verification = SalonVerification.create(salon.id, command.reviewerId)
        return verificationRepository.save(verification)
    }
}

data class ListPendingVerificationsQuery(val callerId: UserId, val page: Int = 0, val size: Int = 20)

/** The Certificates review queue - every case currently PENDING or UNDER_REVIEW, across every salon. */
class ListPendingVerificationsUseCase(
    private val verificationRepository: SalonVerificationRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListPendingVerificationsQuery): PageResult<SalonVerification> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        return verificationRepository.findAllOpen(PageRequest(query.page, query.size))
    }
}

data class StartVerificationReviewCommand(val salonId: SalonId, val verificationId: SalonVerificationId, val reviewerId: UserId)

class StartVerificationReviewUseCase(
    private val verificationRepository: SalonVerificationRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: StartVerificationReviewCommand): SalonVerification {
        platformAuthorization.requirePlatformReviewerOrAdmin(command.reviewerId)
        val verification = verificationRepository.findByIdAndSalonId(command.verificationId, command.salonId)
            ?: throw SalonVerificationNotFoundException(command.verificationId.value.toString())
        verification.startReview(command.reviewerId)
        return verificationRepository.save(verification)
    }
}

/**
 * [verifiedNeighborhood]/[verifiedCityCenter] are optional and independent of
 * the rest of this command - geographic verification is its own item, never
 * a precondition for approving the case overall (approved design). Supplying
 * only one of the pair is treated as "no geo decision this case" rather than
 * a partial one - [SalonGeoClassificationReview.recordVerification] itself
 * requires both together.
 */
data class ApproveSalonVerificationCommand(
    val salonId: SalonId,
    val verificationId: SalonVerificationId,
    val reviewerId: UserId,
    val qualityScore: Int? = null,
    val decorScore: Int? = null,
    val verifiedNeighborhood: Boolean? = null,
    val verifiedCityCenter: Boolean? = null,
)

/**
 * [allDocumentsApproved] (required by [SalonVerification.approve]'s own
 * domain guard) is computed here from the real, individually-reviewed
 * document states - every document actually linked to this case must itself
 * be [DocumentVerificationStatus.APPROVED] - rather than trusted as a
 * caller-supplied flag. On success, [recomputeRojanVerifiedProjection] is the
 * only place [ai.rojan.backend.domain.salon.Salon.rojanVerified] is written,
 * and nothing here ever touches [ai.rojan.backend.domain.salon.Salon.active]/
 * [ai.rojan.backend.domain.salon.Salon.onboardingStatus].
 */
class ApproveSalonVerificationUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
    private val documentRepository: SalonDocumentRepository,
    private val geoClassificationReviewRepository: SalonGeoClassificationReviewRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ApproveSalonVerificationCommand): SalonVerification {
        platformAuthorization.requirePlatformReviewerOrAdmin(command.reviewerId)
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        val verification = verificationRepository.findByIdAndSalonId(command.verificationId, command.salonId)
            ?: throw SalonVerificationNotFoundException(command.verificationId.value.toString())

        val linkedDocumentIds = verificationDocumentRepository.findDocumentIdsByVerificationId(verification.id)
        val allDocumentsApproved = linkedDocumentIds.all { documentId ->
            documentRepository.findByIdAndSalonId(documentId, salon.id)?.verificationStatus == DocumentVerificationStatus.APPROVED
        }

        verification.approve(command.reviewerId, allDocumentsApproved, command.qualityScore, command.decorScore)
        val saved = verificationRepository.save(verification)

        if (command.verifiedNeighborhood != null && command.verifiedCityCenter != null) {
            val geoReview = SalonGeoClassificationReview.create(
                salonId = salon.id,
                declaredNeighborhood = salon.isNeighborhoodSalon ?: false,
                declaredCityCenter = salon.isCityCenterSalon ?: false,
                verificationId = verification.id,
            )
            geoReview.recordVerification(command.verifiedNeighborhood, command.verifiedCityCenter)
            geoClassificationReviewRepository.save(geoReview)
        }

        recomputeRojanVerifiedProjection(salon, verificationRepository, salonRepository)
        return saved
    }
}

/**
 * A rejection leaves [ai.rojan.backend.domain.salon.Salon.active]/
 * [ai.rojan.backend.domain.salon.Salon.onboardingStatus] completely untouched
 * - an already-ACTIVE salon stays ACTIVE and publicly discoverable; only
 * [ai.rojan.backend.domain.salon.Salon.rojanVerified] (via
 * [recomputeRojanVerifiedProjection]) can change as a result. No automatic
 * deactivation exists anywhere in this use case.
 */
data class RejectSalonVerificationCommand(
    val salonId: SalonId,
    val verificationId: SalonVerificationId,
    val reviewerId: UserId,
    val reason: String,
)

class RejectSalonVerificationUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: RejectSalonVerificationCommand): SalonVerification {
        platformAuthorization.requirePlatformReviewerOrAdmin(command.reviewerId)
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        val verification = verificationRepository.findByIdAndSalonId(command.verificationId, command.salonId)
            ?: throw SalonVerificationNotFoundException(command.verificationId.value.toString())

        verification.reject(command.reviewerId, command.reason)
        val saved = verificationRepository.save(verification)

        recomputeRojanVerifiedProjection(salon, verificationRepository, salonRepository)
        return saved
    }
}

// ---- Platform Authority read access (API contract-completion phase) ----
//
// Web Phase 1 (Certificates workspace) discovered these were genuinely missing: a platform caller
// had no way to re-fetch a single case, its history, or its geo classification independent of the
// open-cases queue. Each use case below is the platform-authorized counterpart to an existing
// manager-scoped one, reusing the exact same [VerificationWithDocuments]/repository query shape -
// never a second verification model, never a new repository method.

data class GetVerificationForPlatformQuery(val salonId: SalonId, val verificationId: SalonVerificationId, val callerId: UserId)

/**
 * Platform Authority counterpart to [GetVerificationUseCase] - same [VerificationWithDocuments]
 * shape, authorized via [PlatformAuthorizationResolver] instead of
 * [ai.rojan.backend.application.salon.SalonPermissionResolver]. Reuses
 * [SalonVerificationRepository.findByIdAndSalonId] (already tenant-scoped) - no new repository
 * method, no relaxation of that method's own "cross-salon reference 404s" discipline.
 */
class GetVerificationForPlatformUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: GetVerificationForPlatformQuery): VerificationWithDocuments {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        salonRepository.findById(query.salonId) ?: throw SalonNotFoundException(query.salonId.value.toString())
        val verification = verificationRepository.findByIdAndSalonId(query.verificationId, query.salonId)
            ?: throw SalonVerificationNotFoundException(query.verificationId.value.toString())
        return VerificationWithDocuments(verification, verificationDocumentRepository.findDocumentIdsByVerificationId(verification.id))
    }
}

data class ListVerificationHistoryForPlatformQuery(val salonId: SalonId, val callerId: UserId)

/**
 * Platform Authority counterpart to [ListVerificationHistoryUseCase] - the exact same full,
 * never-collapsed case history (every [SalonVerification] ever submitted for the salon, newest
 * first), authorized via [PlatformAuthorizationResolver] instead of owner-identity. Lets the
 * Certificates case-detail UI see prior cases without going through the manager-only endpoint.
 */
class ListVerificationHistoryForPlatformUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListVerificationHistoryForPlatformQuery): List<VerificationWithDocuments> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        salonRepository.findById(query.salonId) ?: throw SalonNotFoundException(query.salonId.value.toString())
        return verificationRepository.findHistoryBySalonId(query.salonId).map { verification ->
            VerificationWithDocuments(verification, verificationDocumentRepository.findDocumentIdsByVerificationId(verification.id))
        }
    }
}

data class GetGeoClassificationForPlatformQuery(val salonId: SalonId, val verificationId: SalonVerificationId, val callerId: UserId)

/**
 * Platform Authority read-only access to one case's [SalonGeoClassificationReview], if one was ever
 * recorded ([ApproveSalonVerificationUseCase] only creates one when the reviewer supplied both
 * `verifiedNeighborhood`/`verifiedCityCenter` on approval - not every case reviews geo
 * classification). `null` is a legitimate, honest "never recorded for this case" result, not an
 * error - the caller (verification case itself) still 404s if it doesn't exist. Reuses
 * [SalonGeoClassificationReviewRepository.findByVerificationId] - no new repository method, no
 * second geo-review entity or use case.
 */
class GetGeoClassificationForPlatformUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val geoClassificationReviewRepository: SalonGeoClassificationReviewRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: GetGeoClassificationForPlatformQuery): SalonGeoClassificationReview? {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        salonRepository.findById(query.salonId) ?: throw SalonNotFoundException(query.salonId.value.toString())
        verificationRepository.findByIdAndSalonId(query.verificationId, query.salonId)
            ?: throw SalonVerificationNotFoundException(query.verificationId.value.toString())
        return geoClassificationReviewRepository.findByVerificationId(query.verificationId)
    }
}
