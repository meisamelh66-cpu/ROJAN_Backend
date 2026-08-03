package ai.rojan.backend.api.salon

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateSpecialistRequest(
    val userId: UUID?,

    @field:NotBlank
    @field:Size(max = 255)
    val displayName: String,

    @field:Size(max = 2000)
    val bio: String?,

    @field:Size(max = 1000)
    val photoUrl: String?,
)

data class UpdateSpecialistRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val displayName: String,

    @field:Size(max = 2000)
    val bio: String?,

    @field:Size(max = 1000)
    val photoUrl: String?,
)

data class SpecialistResponse(
    val id: UUID,
    val salonId: UUID,
    val userId: UUID?,
    val displayName: String,
    val bio: String?,
    val photoUrl: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
