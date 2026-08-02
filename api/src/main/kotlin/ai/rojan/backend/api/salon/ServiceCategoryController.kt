package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.CreateServiceCategoryCommand
import ai.rojan.backend.application.salon.CreateServiceCategoryUseCase
import ai.rojan.backend.application.salon.DeactivateServiceCategoryCommand
import ai.rojan.backend.application.salon.DeactivateServiceCategoryUseCase
import ai.rojan.backend.application.salon.UpdateServiceCategoryCommand
import ai.rojan.backend.application.salon.UpdateServiceCategoryUseCase
import ai.rojan.backend.domain.common.ServiceCategoryNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
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
@RequestMapping("/api/v1/salons/{salonId}/categories")
@Tag(name = "Service Categories")
class ServiceCategoryController(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val createServiceCategoryUseCase: CreateServiceCategoryUseCase,
    private val updateServiceCategoryUseCase: UpdateServiceCategoryUseCase,
    private val deactivateServiceCategoryUseCase: DeactivateServiceCategoryUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a service category to a salon (owner only)")
    fun create(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: CreateServiceCategoryRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ServiceCategoryResponse {
        val callerId = currentUserResolver.resolve(principal)
        val category = createServiceCategoryUseCase.execute(
            CreateServiceCategoryCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                name = request.name,
                description = request.description,
            ),
        )
        return category.toResponse()
    }

    @GetMapping
    @Operation(summary = "List service categories for a salon")
    fun list(@PathVariable salonId: UUID): List<ServiceCategoryResponse> =
        serviceCategoryRepository.findBySalonId(SalonId(salonId)).map { it.toResponse() }

    @GetMapping("/{categoryId}")
    @Operation(summary = "Get a service category by id")
    fun get(@PathVariable salonId: UUID, @PathVariable categoryId: UUID): ServiceCategoryResponse =
        findCategoryOrThrow(salonId, categoryId).toResponse()

    @PutMapping("/{categoryId}")
    @Operation(summary = "Update a service category (owner only)")
    fun update(
        @PathVariable salonId: UUID,
        @PathVariable categoryId: UUID,
        @Valid @RequestBody request: UpdateServiceCategoryRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ServiceCategoryResponse {
        val callerId = currentUserResolver.resolve(principal)
        val category = findCategoryOrThrow(salonId, categoryId)
        val updated = updateServiceCategoryUseCase.execute(
            UpdateServiceCategoryCommand(
                categoryId = category.id,
                callerId = callerId,
                name = request.name,
                description = request.description,
            ),
        )
        return updated.toResponse()
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a service category (owner only)")
    fun deactivate(
        @PathVariable salonId: UUID,
        @PathVariable categoryId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        val category = findCategoryOrThrow(salonId, categoryId)
        deactivateServiceCategoryUseCase.execute(DeactivateServiceCategoryCommand(category.id, callerId))
    }

    private fun findCategoryOrThrow(salonId: UUID, categoryId: UUID): ServiceCategory =
        serviceCategoryRepository.findById(ServiceCategoryId(categoryId))
            ?.takeIf { it.salonId == SalonId(salonId) }
            ?: throw ServiceCategoryNotFoundException(categoryId.toString())

    private fun ServiceCategory.toResponse() = ServiceCategoryResponse(
        id = id.value,
        salonId = salonId.value,
        name = name,
        description = description,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
