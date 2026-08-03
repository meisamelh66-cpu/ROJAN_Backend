package ai.rojan.backend.api.salon

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateServiceRequest(
    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(example = "Haircut")
    val name: String,

    @field:Size(max = 2000)
    @field:Schema(example = "Classic cut with wash and style")
    val description: String?,

    @field:Positive
    @field:Schema(example = "30", description = "Used to compute a booking's end time and to size available slots")
    val durationMinutes: Int,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Schema(example = "25.00")
    val price: BigDecimal,
)

data class UpdateServiceRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:Size(max = 2000)
    val description: String?,

    @field:Positive
    val durationMinutes: Int,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    val price: BigDecimal,
)

data class ServiceResponse(
    val id: UUID,
    val salonId: UUID,
    val categoryId: UUID,
    val name: String,
    val description: String?,
    val durationMinutes: Int,
    val price: BigDecimal,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
