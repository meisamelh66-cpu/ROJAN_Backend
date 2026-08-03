package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SalonId
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID

@JvmInline
value class WorkingHoursId(val value: UUID) {
    companion object {
        fun new(): WorkingHoursId = WorkingHoursId(UUID.randomUUID())
    }
}

/**
 * A salon's operating hours for one day of the week. May hold multiple
 * non-overlapping work intervals (e.g. split by a lunch break). The absence
 * of a [WorkingHours] row for a given (salon, day) means the salon is closed
 * that day.
 */
class WorkingHours private constructor(
    val id: WorkingHoursId,
    val salonId: SalonId,
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
        fun create(salonId: SalonId, dayOfWeek: DayOfWeek, intervals: List<TimeInterval>): WorkingHours {
            val now = Instant.now()
            return WorkingHours(WorkingHoursId.new(), salonId, dayOfWeek, validated(intervals), now, now)
        }

        fun reconstitute(
            id: WorkingHoursId,
            salonId: SalonId,
            dayOfWeek: DayOfWeek,
            intervals: List<TimeInterval>,
            createdAt: Instant,
            updatedAt: Instant,
        ): WorkingHours = WorkingHours(id, salonId, dayOfWeek, intervals, createdAt, updatedAt)

        private fun validated(intervals: List<TimeInterval>): List<TimeInterval> {
            require(intervals.isNotEmpty()) { "Working hours must include at least one interval" }
            val sorted = intervals.sortedBy { it.start }
            for (i in 0 until sorted.size - 1) {
                require(!sorted[i].overlaps(sorted[i + 1])) { "Working hour intervals must not overlap" }
            }
            return sorted
        }
    }
}
