package ai.rojan.backend.api.customer

import ai.rojan.backend.domain.customer.CustomerStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateCustomerRequest(
    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(example = "Jane Doe")
    val fullName: String,

    @field:Size(max = 20)
    @field:Schema(example = "+989123456789", description = "E.164 format. At least one of phoneNumber/email is required (enforced by the domain, not a Bean Validation rule - same as User's own email-or-phone invariant).")
    val phoneNumber: String?,

    @field:Size(max = 255)
    @field:Schema(example = "jane.doe@example.com")
    val email: String?,

    @field:Size(max = 255)
    @field:Schema(example = "Acme Corp")
    val company: String?,
)

/** Reception-facing counterpart to [CreateCustomerRequest] - no `company` field to even omit; see `ROJAN_Reception_Permission_Contract_Update_ADR_v1.md`. */
data class CreateCustomerIdentityRequest(
    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(example = "Jane Doe")
    val fullName: String,

    @field:Size(max = 20)
    @field:Schema(example = "+989123456789")
    val phoneNumber: String?,

    @field:Size(max = 255)
    @field:Schema(example = "jane.doe@example.com")
    val email: String?,
)

/** Every field is optional and means "leave unchanged" when absent - see `UpdateCustomerUseCase`'s own doc comment for the exact PATCH merge semantics. */
data class UpdateCustomerRequest(
    @field:Size(max = 255)
    val fullName: String?,

    @field:Size(max = 20)
    val phoneNumber: String?,

    @field:Size(max = 255)
    val email: String?,

    @field:Size(max = 255)
    val company: String?,

    val status: CustomerStatus?,
)

data class AddCustomerNoteRequest(
    @field:NotBlank
    @field:Size(max = 2000)
    val text: String,
)

data class AddCustomerTagRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val label: String,
)

data class CustomerResponse(
    val id: UUID,
    val salonId: UUID,
    @field:Schema(description = "Linked backend account, if any - null for a walk-in customer with no app account.")
    val userId: UUID?,
    val fullName: String,
    val phoneNumber: String?,
    val email: String?,
    val company: String?,
    val status: CustomerStatus,
    @field:Schema(description = "Sum of completed-booking service prices for this customer, computed on read - zero if userId is null (no booking data to sum).")
    val lifetimeValue: BigDecimal,
    val tags: List<String>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Reception-facing counterpart to [CustomerResponse] - structurally omits `company`/`tags`/`lifetimeValue`, not just by convention. See `ROJAN_Reception_Permission_Contract_Update_ADR_v1.md`. */
data class CustomerIdentityResponse(
    val id: UUID,
    val salonId: UUID,
    val fullName: String,
    val phoneNumber: String?,
    val email: String?,
    val active: Boolean,
)

data class CustomerTagResponse(val id: UUID, val label: String, val createdAt: Instant)

data class CustomerNoteResponse(val id: UUID, val authorId: UUID, val text: String, val createdAt: Instant)

/** [type] is one of `STATUS_CHANGED | TAG_ADDED | TAG_REMOVED | NOTE | BOOKING_CREATED | BOOKING_CONFIRMED | BOOKING_COMPLETED | BOOKING_CANCELLED` - see `GetCustomerTimelineUseCase`'s own doc comment for how these are merged. */
data class CustomerTimelineEntryResponse(val type: String, val description: String, val occurredAt: Instant)
