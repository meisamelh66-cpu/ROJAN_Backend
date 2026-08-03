package ai.rojan.backend.domain.schedule

/** Pure interval-arithmetic helpers over sets of [TimeInterval]s. No I/O, fully deterministic. */
object IntervalMath {

    /** Sorts and merges overlapping/adjacent intervals into the smallest equivalent set. */
    fun normalize(intervals: List<TimeInterval>): List<TimeInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.start }
        val merged = mutableListOf(sorted.first())
        for (interval in sorted.drop(1)) {
            val last = merged.last()
            if (interval.start <= last.end) {
                if (interval.end > last.end) merged[merged.lastIndex] = TimeInterval(last.start, interval.end)
            } else {
                merged += interval
            }
        }
        return merged
    }

    /** The overlap between every interval in [a] and every interval in [b]. */
    fun intersect(a: List<TimeInterval>, b: List<TimeInterval>): List<TimeInterval> {
        val normalizedA = normalize(a)
        val normalizedB = normalize(b)
        val result = mutableListOf<TimeInterval>()
        for (x in normalizedA) {
            for (y in normalizedB) {
                val start = maxOf(x.start, y.start)
                val end = minOf(x.end, y.end)
                if (start < end) result += TimeInterval(start, end)
            }
        }
        return normalize(result)
    }

    /** [from] with every interval in [minus] carved out of it. */
    fun subtract(from: List<TimeInterval>, minus: List<TimeInterval>): List<TimeInterval> {
        var remaining = normalize(from)
        for (busy in normalize(minus)) {
            val next = mutableListOf<TimeInterval>()
            for (interval in remaining) {
                if (!interval.overlaps(busy)) {
                    next += interval
                    continue
                }
                if (busy.start > interval.start) next += TimeInterval(interval.start, minOf(busy.start, interval.end))
                if (busy.end < interval.end) next += TimeInterval(maxOf(busy.end, interval.start), interval.end)
            }
            remaining = next
        }
        return remaining
    }
}
