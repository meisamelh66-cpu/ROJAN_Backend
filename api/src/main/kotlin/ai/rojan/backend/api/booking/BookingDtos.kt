package ai.rojan.backend.api.booking

import ai.rojan.backend.domain.booking.BookingStatus
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
    val startTime: LocalDateTime,

    @field:Size(max = 1000)
    val notes: String?,
)

data class RescheduleBookingRequest(
    @field:NotNull
    @field:Future
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
    val status: BookingStatus,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
