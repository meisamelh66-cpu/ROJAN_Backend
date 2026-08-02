package ai.rojan.backend.api.salon

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateBranchRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:NotBlank
    @field:Size(max = 500)
    val address: String,

    @field:NotBlank
    @field:Size(max = 32)
    val phone: String,
)

data class UpdateBranchRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:NotBlank
    @field:Size(max = 500)
    val address: String,

    @field:NotBlank
    @field:Size(max = 32)
    val phone: String,
)

data class BranchResponse(
    val id: UUID,
    val salonId: UUID,
    val name: String,
    val address: String,
    val phone: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
