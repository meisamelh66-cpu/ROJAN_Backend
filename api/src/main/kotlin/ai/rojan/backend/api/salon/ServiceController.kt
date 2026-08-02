package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.CreateServiceCommand
import ai.rojan.backend.application.salon.CreateServiceUseCase
import ai.rojan.backend.application.salon.DeactivateServiceCommand
import ai.rojan.backend.application.salon.DeactivateServiceUseCase
import ai.rojan.backend.application.salon.UpdateServiceCommand
import ai.rojan.backend.application.salon.UpdateServiceUseCase
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
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
@RequestMapping("/api/v1/salons/{salonId}/categories/{categoryId}/services")
@Tag(name = "Services")
class ServiceController(
    private val serviceRepository: ServiceRepository,
    private val createServiceUseCase: CreateServiceUseCase,
    private val updateServiceUseCase: UpdateServiceUseCase,
    private val deactivateServiceUseCase: DeactivateServiceUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a service to a category (owner only)")
    fun create(
        @PathVariable salonId: UUID,
        @PathVariable categoryId: UUID,
        @Valid @RequestBody request: CreateServiceRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ServiceResponse {
        val callerId = currentUserResolver.resolve(principal)
        val service = createServiceUseCase.execute(
            CreateServiceCommand(
                salonId = SalonId(salonId),
                categoryId = ServiceCategoryId(categoryId),
                callerId = callerId,
                name = request.name,
                description = request.description,
                durationMinutes = request.durationMinutes,
                price = request.price,
            ),
        )
        return service.toResponse()
    }

    @GetMapping
    @Operation(summary = "List services in a category")
    fun list(@PathVariable salonId: UUID, @PathVariable categoryId: UUID): List<ServiceResponse> =
        serviceRepository.findByCategoryId(ServiceCategoryId(categoryId))
            .filter { it.salonId == SalonId(salonId) }
            .map { it.toResponse() }

    @GetMapping("/{serviceId}")
    @Operation(summary = "Get a service by id")
    fun get(
        @PathVariable salonId: UUID,
        @PathVariable categoryId: UUID,
        @PathVariable serviceId: UUID,
    ): ServiceResponse = findServiceOrThrow(salonId, categoryId, serviceId).toResponse()

    @PutMapping("/{serviceId}")
    @Operation(summary = "Update a service (owner only)")
    fun update(
        @PathVariable salonId: UUID,
        @PathVariable categoryId: UUID,
        @PathVariable serviceId: UUID,
        @Valid @RequestBody request: UpdateServiceRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ServiceResponse {
        val callerId = currentUserResolver.resolve(principal)
        val service = findServiceOrThrow(salonId, categoryId, serviceId)
        val updated = updateServiceUseCase.execute(
            UpdateServiceCommand(
                serviceId = service.id,
                callerId = callerId,
                name = request.name,
                description = request.description,
                durationMinutes = request.durationMinutes,
                price = request.price,
            ),
        )
        return updated.toResponse()
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a service (owner only)")
    fun deactivate(
        @PathVariable salonId: UUID,
        @PathVariable categoryId: UUID,
        @PathVariable serviceId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        val service = findServiceOrThrow(salonId, categoryId, serviceId)
        deactivateServiceUseCase.execute(DeactivateServiceCommand(service.id, callerId))
    }

    private fun findServiceOrThrow(salonId: UUID, categoryId: UUID, serviceId: UUID): Service =
        serviceRepository.findById(ServiceId(serviceId))
            ?.takeIf { it.salonId == SalonId(salonId) && it.categoryId == ServiceCategoryId(categoryId) }
            ?: throw ServiceNotFoundException(serviceId.toString())

    private fun Service.toResponse() = ServiceResponse(
        id = id.value,
        salonId = salonId.value,
        categoryId = categoryId.value,
        name = name,
        description = description,
        durationMinutes = durationMinutes,
        price = price,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
