package ai.rojan.backend.api.salon

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateServiceCategoryRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:Size(max = 2000)
    val description: String?,
)

data class UpdateServiceCategoryRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:Size(max = 2000)
    val description: String?,
)

data class ServiceCategoryResponse(
    val id: UUID,
    val salonId: UUID,
    val name: String,
    val description: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Default Beauty Salon Category Catalog - a suggestion, never a persisted
 * [ServiceCategoryResponse]: no [id]/[salonId] because nothing was created
 * yet. Distinguishable from a real category by this shape alone (callers
 * never see an [id] to confuse the two) - a salon only gets a real category
 * row once it actually creates one via `POST .../categories`.
 */
data class DefaultServiceCategoryResponse(
    val name: String,
    val serviceTypes: List<String>,
)
