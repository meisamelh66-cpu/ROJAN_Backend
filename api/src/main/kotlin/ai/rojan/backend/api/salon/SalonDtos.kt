package ai.rojan.backend.api.salon

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateSalonRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:Size(max = 2000)
    val description: String?,

    @field:NotBlank
    @field:Size(max = 32)
    val phone: String,

    @field:Email
    @field:Size(max = 255)
    val email: String?,

    @field:NotBlank
    @field:Size(max = 500)
    val address: String,
)

data class UpdateSalonRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:Size(max = 2000)
    val description: String?,

    @field:NotBlank
    @field:Size(max = 32)
    val phone: String,

    @field:Email
    @field:Size(max = 255)
    val email: String?,

    @field:NotBlank
    @field:Size(max = 500)
    val address: String,
)

data class SalonResponse(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
