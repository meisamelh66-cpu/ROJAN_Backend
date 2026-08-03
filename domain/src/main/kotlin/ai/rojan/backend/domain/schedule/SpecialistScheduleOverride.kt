package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class ScheduleOverrideId(val value: UUID) {
    companion object {
        fun new(): ScheduleOverrideId = ScheduleOverrideId(UUID.randomUUID())
    }
}

/**
 * A one-off replacement of a specialist's normal weekly availability for a
 * single [date]. An empty [intervals] list means the specialist is fully
 * unavailable that day, without it being a full [SpecialistLeave].
 */
class SpecialistScheduleOverride private constructor(
    val id: ScheduleOverrideId,
    val specialistId: SpecialistId,
    val date: LocalDate,
    intervals: List<TimeInterval>,
    reason: String?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var intervals: List<TimeInterval> = intervals
        private set

    var reason: String? = reason
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(newIntervals: List<TimeInterval>, newReason: String?) {
        intervals = validated(newIntervals)
        reason = newReason?.trim()?.ifBlank { null }
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            specialistId: SpecialistId,
            date: LocalDate,
            intervals: List<TimeInterval>,
            reason: String?,
        ): SpecialistScheduleOverride {
            val now = Instant.now()
            return SpecialistScheduleOverride(
                ScheduleOverrideId.new(),
                specialistId,
                date,
                validated(intervals),
                reason?.trim()?.ifBlank { null },
                now,
                now,
            )
        }

        fun reconstitute(
            id: ScheduleOverrideId,
            specialistId: SpecialistId,
            date: LocalDate,
            intervals: List<TimeInterval>,
            reason: String?,
            createdAt: Instant,
            updatedAt: Instant,
        ): SpecialistScheduleOverride =
            SpecialistScheduleOverride(id, specialistId, date, intervals, reason, createdAt, updatedAt)

        private fun validated(intervals: List<TimeInterval>): List<TimeInterval> {
            val sorted = intervals.sortedBy { it.start }
            for (i in 0 until sorted.size - 1) {
                require(!sorted[i].overlaps(sorted[i + 1])) { "Override intervals must not overlap" }
            }
            return sorted
        }
    }
}
