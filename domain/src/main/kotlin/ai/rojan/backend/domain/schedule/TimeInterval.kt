package ai.rojan.backend.domain.schedule

import java.time.LocalTime

/** A half-open [start, end) window within a single day. */
data class TimeInterval(val start: LocalTime, val end: LocalTime) {
    init {
        require(start < end) { "Interval start ($start) must be before end ($end)" }
    }

    fun overlaps(other: TimeInterval): Boolean = start < other.end && other.start < end
}
