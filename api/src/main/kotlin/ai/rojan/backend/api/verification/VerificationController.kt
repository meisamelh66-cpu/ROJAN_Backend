package ai.rojan.backend.api.verification

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.verification.GetVerificationUseCase
import ai.rojan.backend.application.verification.ListVerificationHistoryUseCase
import ai.rojan.backend.application.verification.SubmitVerificationCommand
import ai.rojan.backend.application.verification.SubmitVerificationUseCase
import ai.rojan.backend.application.verification.VerificationWithDocuments
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Salon Verification Foundation (Phase 3) - submit and read only. Review,
 * approve, and reject are deliberately not exposed here: they require a
 * Platform Authority caller that doesn't exist in this codebase yet
 * (Phase 3 architecture §04, explicitly out of scope for this
 * implementation). The underlying domain state machine already supports
 * those transitions - see [ai.rojan.backend.domain.verification.SalonVerification] -
 * they simply have no use case or route wired to them.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/verification")
@Tag(name = "Verification")
class VerificationController(
    private val submitVerificationUseCase: SubmitVerificationUseCase,
    private val getVerificationUseCase: GetVerificationUseCase,
    private val listVerificationHistoryUseCase: ListVerificationHistoryUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a verification case for a salon (owner only)")
    fun submit(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: SubmitVerificationRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        val result = submitVerificationUseCase.execute(
            SubmitVerificationCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                documentIds = request.documentIds.map { SalonDocumentId(it) },
            ),
        )
        return result.toResponse()
    }

    @GetMapping
    @Operation(summary = "Get the current verification case for a salon (owner or VIEW_DOCUMENTS/MANAGE_DOCUMENTS)")
    fun get(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonVerificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return getVerificationUseCase.execute(SalonId(salonId), callerId).toResponse()
    }

    @GetMapping("/history")
    @Operation(summary = "List every verification case ever submitted for a salon, newest first (owner only)")
    fun history(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<SalonVerificationResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listVerificationHistoryUseCase.execute(SalonId(salonId), callerId).map { it.toResponse() }
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
        documentIds = documentIds.map { it.value },
        createdAt = verification.createdAt,
        updatedAt = verification.updatedAt,
    )
}
