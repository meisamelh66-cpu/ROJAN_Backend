package ai.rojan.backend.api.salon

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateSalonRequest(
    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(example = "Glow Salon")
    val name: String,

    @field:Size(max = 2000)
    @field:Schema(example = "Full-service hair and beauty salon in downtown.")
    val description: String?,

    @field:NotBlank
    @field:Size(max = 32)
    @field:Schema(example = "+1 555 0100")
    val phone: String,

    @field:Email
    @field:Size(max = 255)
    @field:Schema(example = "hello@glowsalon.example")
    val email: String?,

    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(example = "1 Main St, Springfield")
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
