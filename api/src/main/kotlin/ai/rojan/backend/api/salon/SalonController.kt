package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.CreateSalonCommand
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.DeactivateSalonCommand
import ai.rojan.backend.application.salon.DeactivateSalonUseCase
import ai.rojan.backend.application.salon.UpdateSalonCommand
import ai.rojan.backend.application.salon.UpdateSalonUseCase
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
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
@RequestMapping("/api/v1/salons")
@Tag(name = "Salons")
class SalonController(
    private val salonRepository: SalonRepository,
    private val createSalonUseCase: CreateSalonUseCase,
    private val updateSalonUseCase: UpdateSalonUseCase,
    private val deactivateSalonUseCase: DeactivateSalonUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new salon owned by the authenticated user")
    fun create(
        @Valid @RequestBody request: CreateSalonRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonResponse {
        val ownerId = currentUserResolver.resolve(principal)
        val salon = createSalonUseCase.execute(
            CreateSalonCommand(
                ownerId = ownerId,
                name = request.name,
                description = request.description,
                phone = request.phone,
                email = request.email,
                address = request.address,
            ),
        )
        return salon.toResponse()
    }

    @GetMapping("/mine")
    @Operation(summary = "List salons owned by the authenticated user")
    fun mine(@AuthenticationPrincipal principal: UserDetails): List<SalonResponse> {
        val ownerId = currentUserResolver.resolve(principal)
        return salonRepository.findByOwnerId(ownerId).map { it.toResponse() }
    }

    @GetMapping
    @Operation(summary = "Browse active salons")
    fun list(): List<SalonResponse> = salonRepository.findAllActive().map { it.toResponse() }

    @GetMapping("/{salonId}")
    @Operation(summary = "Get a salon by id")
    fun get(@PathVariable salonId: UUID): SalonResponse =
        findSalonOrThrow(salonId).toResponse()

    @PutMapping("/{salonId}")
    @Operation(summary = "Update a salon (owner only)")
    fun update(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: UpdateSalonRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = updateSalonUseCase.execute(
            UpdateSalonCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                name = request.name,
                description = request.description,
                phone = request.phone,
                email = request.email,
                address = request.address,
            ),
        )
        return salon.toResponse()
    }

    @DeleteMapping("/{salonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a salon (owner only)")
    fun deactivate(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails) {
        val callerId = currentUserResolver.resolve(principal)
        deactivateSalonUseCase.execute(DeactivateSalonCommand(SalonId(salonId), callerId))
    }

    private fun findSalonOrThrow(salonId: UUID): Salon =
        salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())

    private fun Salon.toResponse() = SalonResponse(
        id = id.value,
        ownerId = ownerId.value,
        name = name,
        description = description,
        phone = phone,
        email = email,
        address = address,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
