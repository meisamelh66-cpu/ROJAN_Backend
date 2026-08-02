package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID

@JvmInline
value class WeeklyAvailabilityId(val value: UUID) {
    companion object {
        fun new(): WeeklyAvailabilityId = WeeklyAvailabilityId(UUID.randomUUID())
    }
}

/** A specialist's recurring weekly working pattern for one day of the week. */
class SpecialistWeeklyAvailability private constructor(
    val id: WeeklyAvailabilityId,
    val specialistId: SpecialistId,
    val dayOfWeek: DayOfWeek,
    intervals: List<TimeInterval>,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var intervals: List<TimeInterval> = intervals
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun updateIntervals(newIntervals: List<TimeInterval>) {
        intervals = validated(newIntervals)
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            specialistId: SpecialistId,
            dayOfWeek: DayOfWeek,
            intervals: List<TimeInterval>,
        ): SpecialistWeeklyAvailability {
            val now = Instant.now()
            return SpecialistWeeklyAvailability(
                WeeklyAvailabilityId.new(),
                specialistId,
                dayOfWeek,
                validated(intervals),
                now,
                now,
            )
        }

        fun reconstitute(
            id: WeeklyAvailabilityId,
            specialistId: SpecialistId,
            dayOfWeek: DayOfWeek,
            intervals: List<TimeInterval>,
            createdAt: Instant,
            updatedAt: Instant,
        ): SpecialistWeeklyAvailability =
            SpecialistWeeklyAvailability(id, specialistId, dayOfWeek, intervals, createdAt, updatedAt)

        private fun validated(intervals: List<TimeInterval>): List<TimeInterval> {
            require(intervals.isNotEmpty()) { "Weekly availability must include at least one interval" }
            val sorted = intervals.sortedBy { it.start }
            for (i in 0 until sorted.size - 1) {
                require(!sorted[i].overlaps(sorted[i + 1])) { "Availability intervals must not overlap" }
            }
            return sorted
        }
    }
}
