package ai.rojan.backend.api.salon

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

// E.164 format (e.g. +989123456789), matching domain.auth.PhoneNumber's own
// validation - kept in sync manually since Bean Validation annotations can't
// reference a domain value class's regex directly.
private const val E164_PATTERN = "^\\+[1-9]\\d{7,14}$"

data class CreateSpecialistRequest(
    val userId: UUID?,

    @field:NotBlank
    @field:Size(max = 255)
    val displayName: String,

    @field:Size(max = 2000)
    val bio: String?,

    @field:Size(max = 1000)
    val photoUrl: String?,

    @field:NotBlank
    @field:Pattern(regexp = E164_PATTERN, message = "must be E.164 format, e.g. +989123456789")
    val mobileNumber: String,

    @field:NotBlank
    @field:Size(max = 100)
    val specialty: String,
)

data class UpdateSpecialistRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val displayName: String,

    @field:Size(max = 2000)
    val bio: String?,

    @field:Size(max = 1000)
    val photoUrl: String?,

    @field:NotBlank
    @field:Pattern(regexp = E164_PATTERN, message = "must be E.164 format, e.g. +989123456789")
    val mobileNumber: String,

    @field:NotBlank
    @field:Size(max = 100)
    val specialty: String,
)

data class SpecialistResponse(
    val id: UUID,
    val salonId: UUID,
    val userId: UUID?,
    val displayName: String,
    val bio: String?,
    val photoUrl: String?,
    val mobileNumber: String?,
    val specialty: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
