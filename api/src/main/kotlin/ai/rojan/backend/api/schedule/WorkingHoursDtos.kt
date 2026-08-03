package ai.rojan.backend.api.schedule

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class TimeIntervalDto(
    @field:NotNull
    val start: LocalTime,

    @field:NotNull
    val end: LocalTime,
)

data class SetWorkingHoursRequest(
    @field:NotEmpty
    val intervals: List<@Valid TimeIntervalDto>,
)

data class WorkingHoursResponse(
    val id: UUID,
    val salonId: UUID,
    val dayOfWeek: DayOfWeek,
    val intervals: List<TimeIntervalDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
