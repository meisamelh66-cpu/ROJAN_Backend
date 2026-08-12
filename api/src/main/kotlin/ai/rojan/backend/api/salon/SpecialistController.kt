package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.AssignServiceToSpecialistCommand
import ai.rojan.backend.application.salon.AssignServiceToSpecialistUseCase
import ai.rojan.backend.application.salon.CreateSpecialistCommand
import ai.rojan.backend.application.salon.CreateSpecialistUseCase
import ai.rojan.backend.application.salon.DeactivateSpecialistCommand
import ai.rojan.backend.application.salon.DeactivateSpecialistUseCase
import ai.rojan.backend.application.salon.RemoveServiceFromSpecialistCommand
import ai.rojan.backend.application.salon.RemoveServiceFromSpecialistUseCase
import ai.rojan.backend.application.salon.UpdateSpecialistCommand
import ai.rojan.backend.application.salon.UpdateSpecialistUseCase
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.salon.SpecialistServiceRepository
import ai.rojan.backend.domain.user.UserId
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
@RequestMapping("/api/v1/salons/{salonId}/specialists")
@Tag(name = "Specialists")
class SpecialistController(
    private val specialistRepository: SpecialistRepository,
    private val specialistServiceRepository: SpecialistServiceRepository,
    private val createSpecialistUseCase: CreateSpecialistUseCase,
    private val updateSpecialistUseCase: UpdateSpecialistUseCase,
    private val deactivateSpecialistUseCase: DeactivateSpecialistUseCase,
    private val assignServiceToSpecialistUseCase: AssignServiceToSpecialistUseCase,
    private val removeServiceFromSpecialistUseCase: RemoveServiceFromSpecialistUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a specialist to a salon (owner only)")
    fun create(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: CreateSpecialistRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SpecialistResponse {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = createSpecialistUseCase.execute(
            CreateSpecialistCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                userId = request.userId?.let { UserId(it) },
                displayName = request.displayName,
                bio = request.bio,
                photoUrl = request.photoUrl,
            ),
        )
        return specialist.toResponse()
    }

    @GetMapping
    @Operation(summary = "List specialists for a salon")
    fun list(@PathVariable salonId: UUID): List<SpecialistResponse> =
        specialistRepository.findBySalonId(SalonId(salonId)).map { it.toResponse() }

    @GetMapping("/{specialistId}")
    @Operation(summary = "Get a specialist by id")
    fun get(@PathVariable salonId: UUID, @PathVariable specialistId: UUID): SpecialistResponse =
        findSpecialistOrThrow(salonId, specialistId).toResponse()

    @PutMapping("/{specialistId}")
    @Operation(summary = "Update a specialist (owner only)")
    fun update(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @Valid @RequestBody request: UpdateSpecialistRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SpecialistResponse {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val updated = updateSpecialistUseCase.execute(
            UpdateSpecialistCommand(
                specialistId = specialist.id,
                callerId = callerId,
                displayName = request.displayName,
                bio = request.bio,
                photoUrl = request.photoUrl,
            ),
        )
        return updated.toResponse()
    }

    @DeleteMapping("/{specialistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a specialist (owner only)")
    fun deactivate(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        deactivateSpecialistUseCase.execute(DeactivateSpecialistCommand(specialist.id, callerId))
    }

    @GetMapping("/{specialistId}/services")
    @Operation(summary = "List the service ids this specialist is eligible to perform (empty means eligible for every service in the salon)")
    fun listEligibleServices(@PathVariable salonId: UUID, @PathVariable specialistId: UUID): List<UUID> {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        return specialistServiceRepository.findServiceIdsBySpecialistId(specialist.id).map { it.value }
    }

    @PutMapping("/{specialistId}/services/{serviceId}")
    @Operation(summary = "Assign a service to a specialist, restricting them to their assigned services (owner only)")
    fun assignService(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable serviceId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        assignServiceToSpecialistUseCase.execute(
            AssignServiceToSpecialistCommand(SalonId(salonId), SpecialistId(specialistId), ServiceId(serviceId), callerId),
        )
    }

    @DeleteMapping("/{specialistId}/services/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a service assignment from a specialist (owner only)")
    fun removeService(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable serviceId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        removeServiceFromSpecialistUseCase.execute(
            RemoveServiceFromSpecialistCommand(SalonId(salonId), SpecialistId(specialistId), ServiceId(serviceId), callerId),
        )
    }

    private fun findSpecialistOrThrow(salonId: UUID, specialistId: UUID): Specialist =
        specialistRepository.findById(SpecialistId(specialistId))
            ?.takeIf { it.salonId == SalonId(salonId) }
            ?: throw SpecialistNotFoundException(specialistId.toString())

    private fun Specialist.toResponse() = SpecialistResponse(
        id = id.value,
        salonId = salonId.value,
        userId = userId?.value,
        displayName = displayName,
        bio = bio,
        photoUrl = photoUrl,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
