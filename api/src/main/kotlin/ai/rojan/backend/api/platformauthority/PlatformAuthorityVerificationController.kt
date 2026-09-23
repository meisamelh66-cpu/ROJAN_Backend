package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.verification.SalonVerificationResponse
import ai.rojan.backend.application.verification.ApproveSalonVerificationCommand
import ai.rojan.backend.application.verification.ApproveSalonVerificationUseCase
import ai.rojan.backend.application.verification.GetGeoClassificationForPlatformQuery
import ai.rojan.backend.application.verification.GetGeoClassificationForPlatformUseCase
import ai.rojan.backend.application.verification.GetVerificationForPlatformQuery
import ai.rojan.backend.application.verification.GetVerificationForPlatformUseCase
import ai.rojan.backend.application.verification.InitiateRojanReviewCommand
import ai.rojan.backend.application.verification.InitiateRojanReviewUseCase
import ai.rojan.backend.application.verification.ListPendingVerificationsQuery
import ai.rojan.backend.application.verification.ListPendingVerificationsUseCase
import ai.rojan.backend.application.verification.ListVerificationHistoryForPlatformQuery
import ai.rojan.backend.application.verification.ListVerificationHistoryForPlatformUseCase
import ai.rojan.backend.application.verification.RejectSalonVerificationCommand
import ai.rojan.backend.application.verification.RejectSalonVerificationUseCase
import ai.rojan.backend.application.verification.StartVerificationReviewCommand
import ai.rojan.backend.application.verification.StartVerificationReviewUseCase
import ai.rojan.backend.application.verification.VerificationWithDocuments
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.verification.SalonVerification
import ai.rojan.backend.domain.verification.SalonVerificationId
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
 * Platform Authority verification review (Phase 5) - PLATFORM_ADMIN and PLATFORM_REVIEWER only,
 * authorized purely against [ai.rojan.backend.domain.user.User.role] via
 * [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver] inside each use case,
 * never [ai.rojan.backend.application.salon.SalonPermissionResolver] - deliberately never mixed with
 * the salon-scoped [ai.rojan.backend.api.verification.VerificationController].
 *
 * Route shape deviates from a flat `/platform-authority/verifications/{verificationId}/...` suggestion:
 * every mutating use case here ([StartVerificationReviewUseCase], [ApproveSalonVerificationUseCase],
 * [RejectSalonVerificationUseCase]) takes both a `salonId` and a `verificationId`, because
 * [ai.rojan.backend.domain.verification.SalonVerificationRepository.findByIdAndSalonId] is
 * deliberately tenant-scoped by construction throughout this codebase (see that method's own doc
 * comment) - there is no bare-id lookup to call instead, and adding one would be a new repository
 * capability outside this phase's scope. `salonId` is therefore a path segment here, same as every
 * other salon-scoped resource in this API. The one exception is `GET /verifications` itself
 * ([ListPendingVerificationsUseCase]), which is genuinely cross-salon and takes no `salonId` at all.
 *
 * API contract-completion phase (post Web Phase 1): [get]/[history]/[geoClassification] close the
 * three read gaps the Certificates workspace found - a platform caller previously had no way to
 * re-fetch a single case, its full history, or its geo classification once it dropped out of the
 * open-cases queue. Each reuses [SalonVerificationResponse] (the exact same manager-facing DTO,
 * never a second verification model) and an existing, already-tenant-scoped repository query - no
 * new repository method, no bare-id lookup.
 */
@RestController
@RequestMapping("/api/v1/platform-authority")
@Tag(name = "Platform Authority - Verification")
class PlatformAuthorityVerificationController(
    private val initiateRojanReviewUseCase: InitiateRojanReviewUseCase,
    private val listPendingVerificationsUseCase: ListPendingVerificationsUseCase,
    private val startVerificationReviewUseCase: StartVerificationReviewUseCase,
    private val approveSalonVerificationUseCase: ApproveSalonVerificationUseCase,
    private val rejectSalonVerificationUseCase: RejectSalonVerificationUseCase,
    private val getVerificationForPlatformUseCase: GetVerificationForPlatformUseCase,
    private val listVerificationHistoryForPlatformUseCase: ListVerificationHistoryForPlatformUseCase,
    private val getGeoClassificationForPlatformUseCase: GetGeoClassificationForPlatformUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping("/verifications")
    @Operation(summary = "List every verification case currently PENDING or UNDER_REVIEW, across every salon (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun listPending(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        val result = listPendingVerificationsUseCase.execute(ListPendingVerificationsQuery(callerId, page, size))
        return PagedVerificationResponse(
            content = result.content.map { it.toResponse() },
            page = result.page,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @PostMapping("/salons/{salonId}/verifications/initiate")
    @Operation(summary = "Open a reviewer/admin-initiated verification case for a salon (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun initiate(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return initiateRojanReviewUseCase.execute(InitiateRojanReviewCommand(SalonId(salonId), callerId)).toResponse()
    }

    @PostMapping("/salons/{salonId}/verifications/{verificationId}/start-review")
    @Operation(summary = "Claim a pending verification case for review (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun startReview(
        @PathVariable salonId: UUID,
        @PathVariable verificationId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return startVerificationReviewUseCase.execute(
            StartVerificationReviewCommand(SalonId(salonId), SalonVerificationId(verificationId), callerId),
        ).toResponse()
    }

    @PostMapping("/salons/{salonId}/verifications/{verificationId}/approve")
    @Operation(summary = "Approve a verification case under review - sets the salon's rojanVerified projection (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun approve(
        @PathVariable salonId: UUID,
        @PathVariable verificationId: UUID,
        @Valid @RequestBody request: ApproveSalonVerificationRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return approveSalonVerificationUseCase.execute(
            ApproveSalonVerificationCommand(
                salonId = SalonId(salonId),
                verificationId = SalonVerificationId(verificationId),
                reviewerId = callerId,
                qualityScore = request.qualityScore,
                decorScore = request.decorScore,
                verifiedNeighborhood = request.verifiedNeighborhood,
                verifiedCityCenter = request.verifiedCityCenter,
            ),
        ).toResponse()
    }

    @PostMapping("/salons/{salonId}/verifications/{verificationId}/reject")
    @Operation(summary = "Reject a verification case under review - never deactivates the salon (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun reject(
        @PathVariable salonId: UUID,
        @PathVariable verificationId: UUID,
        @Valid @RequestBody request: RejectSalonVerificationRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return rejectSalonVerificationUseCase.execute(
            RejectSalonVerificationCommand(SalonId(salonId), SalonVerificationId(verificationId), callerId, request.reason),
        ).toResponse()
    }

    @GetMapping("/salons/{salonId}/verifications/{verificationId}")
    @Operation(summary = "Get a single verification case by id, open or concluded, with its linked document ids (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun get(
        @PathVariable salonId: UUID,
        @PathVariable verificationId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return getVerificationForPlatformUseCase.execute(
            GetVerificationForPlatformQuery(SalonId(salonId), SalonVerificationId(verificationId), callerId),
        ).toResponse()
    }

    @GetMapping("/salons/{salonId}/verifications/history")
    @Operation(summary = "List every verification case ever submitted for a salon, newest first (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun history(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<SalonVerificationResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listVerificationHistoryForPlatformUseCase.execute(
            ListVerificationHistoryForPlatformQuery(SalonId(salonId), callerId),
        ).map { it.toResponse() }
    }

    @GetMapping("/salons/{salonId}/verifications/{verificationId}/geo-classification")
    @Operation(summary = "Get the geo classification review for a case, if one was recorded - every field null otherwise (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun geoClassification(
        @PathVariable salonId: UUID,
        @PathVariable verificationId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): GeoClassificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        val review = getGeoClassificationForPlatformUseCase.execute(
            GetGeoClassificationForPlatformQuery(SalonId(salonId), SalonVerificationId(verificationId), callerId),
        )
        return GeoClassificationResponse(
            declaredNeighborhood = review?.declaredNeighborhood,
            declaredCityCenter = review?.declaredCityCenter,
            verifiedNeighborhood = review?.verifiedNeighborhood,
            verifiedCityCenter = review?.verifiedCityCenter,
        )
    }

    private fun VerificationWithDocuments.toResponse() = SalonVerificationResponse(
        id = verification.id.value,
        salonId = verification.salonId.value,
        status = verification.status,
        submittedBy = verification.submittedBy.value,
        submittedAt = verification.submittedAt,
        reviewedBy = verification.reviewedBy?.value,
        reviewedAt = verification.reviewedAt,
        rejectionReason = verification.rejectionReason,
        qualityScore = verification.qualityScore,
        decorScore = verification.decorScore,
        documentIds = documentIds.map { it.value },
        createdAt = verification.createdAt,
        updatedAt = verification.updatedAt,
    )

    private fun SalonVerification.toResponse() = PlatformVerificationResponse(
        id = id.value,
        salonId = salonId.value,
        status = status,
        submittedBy = submittedBy.value,
        submittedAt = submittedAt,
        reviewedBy = reviewedBy?.value,
        reviewedAt = reviewedAt,
        rejectionReason = rejectionReason,
        qualityScore = qualityScore,
        decorScore = decorScore,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
