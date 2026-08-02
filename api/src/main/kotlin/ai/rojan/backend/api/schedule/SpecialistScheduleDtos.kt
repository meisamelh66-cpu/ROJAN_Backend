package ai.rojan.backend.api.schedule

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class SetWeeklyAvailabilityRequest(
    @field:NotEmpty
    val intervals: List<@Valid TimeIntervalDto>,
)

data class WeeklyAvailabilityResponse(
    val id: UUID,
    val specialistId: UUID,
    val dayOfWeek: DayOfWeek,
    val intervals: List<TimeIntervalDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SetScheduleOverrideRequest(
    @field:NotNull
    val date: LocalDate,

    val intervals: List<@Valid TimeIntervalDto> = emptyList(),

    @field:Size(max = 500)
    val reason: String?,
)

data class ScheduleOverrideResponse(
    val id: UUID,
    val specialistId: UUID,
    val date: LocalDate,
    val intervals: List<TimeIntervalDto>,
    val reason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CreateLeaveRequest(
    @field:NotNull
    val startDate: LocalDate,

    @field:NotNull
    val endDate: LocalDate,

    @field:Size(max = 500)
    val reason: String?,
)

data class LeaveResponse(
    val id: UUID,
    val specialistId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String?,
    val createdAt: Instant,
)

data class CreateBlockRequest(
    @field:NotNull
    val date: LocalDate,

    @field:NotNull
    val start: LocalTime,

    @field:NotNull
    val end: LocalTime,

    @field:Size(max = 500)
    val reason: String?,
)

data class BlockResponse(
    val id: UUID,
    val specialistId: UUID,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val reason: String?,
    val createdAt: Instant,
)
