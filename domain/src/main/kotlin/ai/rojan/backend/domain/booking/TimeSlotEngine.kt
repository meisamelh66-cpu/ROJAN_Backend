package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.schedule.IntervalMath
import ai.rojan.backend.domain.schedule.TimeInterval
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure calculator that turns a day's free [TimeInterval]s into bookable
 * [TimeSlot]s of a fixed service duration, stepped at a configurable
 * granularity. No I/O: callers gather availability and existing bookings
 * first, so this stays trivially unit-testable.
 */
object TimeSlotEngine {

    fun generateSlots(
        date: LocalDate,
        availableIntervals: List<TimeInterval>,
        busyIntervals: List<TimeInterval>,
        serviceDurationMinutes: Int,
        slotIntervalMinutes: Int,
        earliestStart: LocalTime? = null,
    ): List<TimeSlot> {
        require(serviceDurationMinutes > 0) { "Service duration must be positive" }
        require(slotIntervalMinutes > 0) { "Slot interval must be positive" }

        val freeIntervals = IntervalMath.subtract(availableIntervals, busyIntervals)
        val slots = mutableListOf<TimeSlot>()

        for (interval in freeIntervals) {
            var slotStart = interval.start
            if (earliestStart != null && slotStart.isBefore(earliestStart)) {
                slotStart = roundUpTo(earliestStart, slotIntervalMinutes)
            }
            while (true) {
                val slotEnd = slotStart.plusMinutes(serviceDurationMinutes.toLong())
                if (slotEnd.isAfter(interval.end) || slotEnd == LocalTime.MIDNIGHT) break
                slots += TimeSlot(date.atTime(slotStart), date.atTime(slotEnd))
                val next = slotStart.plusMinutes(slotIntervalMinutes.toLong())
                if (!next.isAfter(slotStart)) break
                slotStart = next
            }
        }
        return slots
    }

    private fun roundUpTo(time: LocalTime, stepMinutes: Int): LocalTime {
        val totalMinutes = time.hour * 60 + time.minute
        val rounded = ((totalMinutes + stepMinutes - 1) / stepMinutes) * stepMinutes
        return LocalTime.of((rounded / 60) % 24, rounded % 60)
    }
}
