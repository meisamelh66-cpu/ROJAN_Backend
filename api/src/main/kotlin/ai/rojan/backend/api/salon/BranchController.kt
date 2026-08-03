package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.CreateBranchCommand
import ai.rojan.backend.application.salon.CreateBranchUseCase
import ai.rojan.backend.application.salon.DeactivateBranchCommand
import ai.rojan.backend.application.salon.DeactivateBranchUseCase
import ai.rojan.backend.application.salon.UpdateBranchCommand
import ai.rojan.backend.application.salon.UpdateBranchUseCase
import ai.rojan.backend.domain.common.BranchNotFoundException
import ai.rojan.backend.domain.salon.Branch
import ai.rojan.backend.domain.salon.BranchId
import ai.rojan.backend.domain.salon.BranchRepository
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons/{salonId}/branches")
@Tag(name = "Branches")
class BranchController(
    private val branchRepository: BranchRepository,
    private val createBranchUseCase: CreateBranchUseCase,
    private val updateBranchUseCase: UpdateBranchUseCase,
    private val deactivateBranchUseCase: DeactivateBranchUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a branch to a salon (owner only)")
    fun create(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: CreateBranchRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): BranchResponse {
        val callerId = currentUserResolver.resolve(principal)
        val branch = createBranchUseCase.execute(
            CreateBranchCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                name = request.name,
                address = request.address,
                phone = request.phone,
            ),
        )
        return branch.toResponse()
    }

    @GetMapping
    @Operation(summary = "List branches for a salon")
    fun list(@PathVariable salonId: UUID): List<BranchResponse> =
        branchRepository.findBySalonId(SalonId(salonId)).map { it.toResponse() }

    @GetMapping("/{branchId}")
    @Operation(summary = "Get a branch by id")
    fun get(@PathVariable salonId: UUID, @PathVariable branchId: UUID): BranchResponse =
        findBranchOrThrow(salonId, branchId).toResponse()

    @PutMapping("/{branchId}")
    @Operation(summary = "Update a branch (owner only)")
    fun update(
        @PathVariable salonId: UUID,
        @PathVariable branchId: UUID,
        @Valid @RequestBody request: UpdateBranchRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): BranchResponse {
        val callerId = currentUserResolver.resolve(principal)
        val branch = findBranchOrThrow(salonId, branchId)
        val updated = updateBranchUseCase.execute(
            UpdateBranchCommand(
                branchId = branch.id,
                callerId = callerId,
                name = request.name,
                address = request.address,
                phone = request.phone,
            ),
        )
        return updated.toResponse()
    }

    @DeleteMapping("/{branchId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a branch (owner only)")
    fun deactivate(
        @PathVariable salonId: UUID,
        @PathVariable branchId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        val branch = findBranchOrThrow(salonId, branchId)
        deactivateBranchUseCase.execute(DeactivateBranchCommand(branch.id, callerId))
    }

    private fun findBranchOrThrow(salonId: UUID, branchId: UUID): Branch =
        branchRepository.findById(BranchId(branchId))
            ?.takeIf { it.salonId == SalonId(salonId) }
            ?: throw BranchNotFoundException(branchId.toString())

    private fun Branch.toResponse() = BranchResponse(
        id = id.value,
        salonId = salonId.value,
        name = name,
        address = address,
        phone = phone,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
