package ai.rojan.backend.domain.schedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalTime

private fun t(start: String, end: String) = TimeInterval(LocalTime.parse(start), LocalTime.parse(end))

class TimeIntervalTest {

    @Test
    fun `rejects a start time that is not before end`() {
        assertThrows(IllegalArgumentException::class.java) { t("10:00", "10:00") }
        assertThrows(IllegalArgumentException::class.java) { t("11:00", "10:00") }
    }

    @Test
    fun `overlaps detects touching and disjoint intervals correctly`() {
        assertEquals(true, t("09:00", "12:00").overlaps(t("11:00", "13:00")))
        assertEquals(false, t("09:00", "12:00").overlaps(t("12:00", "13:00")))
        assertEquals(false, t("09:00", "10:00").overlaps(t("11:00", "12:00")))
    }
}

class IntervalMathTest {

    @Test
    fun `normalize merges overlapping and adjacent intervals`() {
        val result = IntervalMath.normalize(listOf(t("09:00", "12:00"), t("11:00", "13:00"), t("14:00", "15:00")))
        assertEquals(listOf(t("09:00", "13:00"), t("14:00", "15:00")), result)
    }

    @Test
    fun `normalize leaves disjoint intervals untouched but sorted`() {
        val result = IntervalMath.normalize(listOf(t("14:00", "15:00"), t("09:00", "10:00")))
        assertEquals(listOf(t("09:00", "10:00"), t("14:00", "15:00")), result)
    }

    @Test
    fun `normalize of empty list is empty`() {
        assertEquals(emptyList<TimeInterval>(), IntervalMath.normalize(emptyList()))
    }

    @Test
    fun `intersect returns only the overlapping portions`() {
        val salonHours = listOf(t("09:00", "17:00"))
        val specialistHours = listOf(t("08:00", "12:00"), t("13:00", "20:00"))
        val result = IntervalMath.intersect(salonHours, specialistHours)
        assertEquals(listOf(t("09:00", "12:00"), t("13:00", "17:00")), result)
    }

    @Test
    fun `intersect of disjoint sets is empty`() {
        assertEquals(emptyList<TimeInterval>(), IntervalMath.intersect(listOf(t("09:00", "10:00")), listOf(t("11:00", "12:00"))))
    }

    @Test
    fun `subtract carves a busy interval out of the middle`() {
        val result = IntervalMath.subtract(listOf(t("09:00", "17:00")), listOf(t("12:00", "13:00")))
        assertEquals(listOf(t("09:00", "12:00"), t("13:00", "17:00")), result)
    }

    @Test
    fun `subtract removes a fully-covering busy interval entirely`() {
        val result = IntervalMath.subtract(listOf(t("09:00", "17:00")), listOf(t("08:00", "18:00")))
        assertEquals(emptyList<TimeInterval>(), result)
    }

    @Test
    fun `subtract trims the left edge`() {
        val result = IntervalMath.subtract(listOf(t("09:00", "17:00")), listOf(t("05:00", "10:00")))
        assertEquals(listOf(t("10:00", "17:00")), result)
    }

    @Test
    fun `subtract trims the right edge`() {
        val result = IntervalMath.subtract(listOf(t("09:00", "17:00")), listOf(t("16:00", "20:00")))
        assertEquals(listOf(t("09:00", "16:00")), result)
    }

    @Test
    fun `subtract with no overlap leaves the interval untouched`() {
        val result = IntervalMath.subtract(listOf(t("09:00", "10:00")), listOf(t("11:00", "12:00")))
        assertEquals(listOf(t("09:00", "10:00")), result)
    }

    @Test
    fun `subtract handles multiple busy intervals across one available window`() {
        val result = IntervalMath.subtract(
            listOf(t("09:00", "17:00")),
            listOf(t("10:00", "10:30"), t("14:00", "14:30")),
        )
        assertEquals(listOf(t("09:00", "10:00"), t("10:30", "14:00"), t("14:30", "17:00")), result)
    }
}
