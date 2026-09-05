package ai.rojan.backend.api.booking

import ai.rojan.backend.domain.booking.BookingStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class CreateBookingRequest(
    @field:NotNull
    val salonId: UUID,

    @field:NotNull
    val serviceId: UUID,

    @field:NotNull
    val specialistId: UUID,

    @field:NotNull
    @field:Future
    @field:Schema(example = "2026-09-01T10:00:00", description = "Must be one of the windows returned by GET .../available-slots")
    val startTime: LocalDateTime,

    @field:Size(max = 1000)
    @field:Schema(example = "First visit, prefers a quiet chair")
    val notes: String? = null,

    @field:Schema(
        description = "Manager Booking Creation Integrity follow-up: the customer this booking is for, when a " +
            "salon owner is creating it on a customer's behalf (must be a customer returned by " +
            "GET .../salons/{salonId}/customers for this salon). Omit for the normal case of a customer " +
            "booking for themselves - the authenticated caller is used instead.",
    )
    val customerId: UUID? = null,
)

data class RescheduleBookingRequest(
    @field:NotNull
    @field:Future
    @field:Schema(example = "2026-09-02T14:00:00")
    val newStartTime: LocalDateTime,
)

data class BookingResponse(
    val id: UUID,
    val salonId: UUID,
    val serviceId: UUID,
    val specialistId: UUID,
    val customerId: UUID,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,

    @field:Schema(description = "PENDING -> CONFIRMED -> COMPLETED, or CANCELLED from PENDING/CONFIRMED")
    val status: BookingStatus,

    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
