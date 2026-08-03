package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.schedule.TimeInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

private val DATE = LocalDate.of(2026, 8, 10)
private fun t(start: String, end: String) = TimeInterval(LocalTime.parse(start), LocalTime.parse(end))

class TimeSlotEngineTest {

    @Test
    fun `generates back-to-back slots that exactly fill the available window`() {
        val slots = TimeSlotEngine.generateSlots(
            date = DATE,
            availableIntervals = listOf(t("09:00", "10:00")),
            busyIntervals = emptyList(),
            serviceDurationMinutes = 30,
            slotIntervalMinutes = 30,
        )
        assertEquals(
            listOf(
                TimeSlot(DATE.atTime(9, 0), DATE.atTime(9, 30)),
                TimeSlot(DATE.atTime(9, 30), DATE.atTime(10, 0)),
            ),
            slots,
        )
    }

    @Test
    fun `slot granularity can be finer than service duration, producing overlapping candidate start times`() {
        val slots = TimeSlotEngine.generateSlots(
            date = DATE,
            availableIntervals = listOf(t("09:00", "10:00")),
            busyIntervals = emptyList(),
            serviceDurationMinutes = 30,
            slotIntervalMinutes = 15,
        )
        assertEquals(
            listOf(
                TimeSlot(DATE.atTime(9, 0), DATE.atTime(9, 30)),
                TimeSlot(DATE.atTime(9, 15), DATE.atTime(9, 45)),
                TimeSlot(DATE.atTime(9, 30), DATE.atTime(10, 0)),
            ),
            slots,
        )
    }

    @Test
    fun `does not offer a slot that would run past the available window`() {
        val slots = TimeSlotEngine.generateSlots(
            date = DATE,
            availableIntervals = listOf(t("09:00", "09:45")),
            busyIntervals = emptyList(),
            serviceDurationMinutes = 30,
            slotIntervalMinutes = 30,
        )
        assertEquals(listOf(TimeSlot(DATE.atTime(9, 0), DATE.atTime(9, 30))), slots)
    }

    @Test
    fun `existing bookings remove overlapping slots`() {
        val slots = TimeSlotEngine.generateSlots(
            date = DATE,
            availableIntervals = listOf(t("09:00", "12:00")),
            busyIntervals = listOf(t("10:00", "10:30")),
            serviceDurationMinutes = 30,
            slotIntervalMinutes = 30,
        )
        assertEquals(
            listOf(
                TimeSlot(DATE.atTime(9, 0), DATE.atTime(9, 30)),
                TimeSlot(DATE.atTime(9, 30), DATE.atTime(10, 0)),
                TimeSlot(DATE.atTime(10, 30), DATE.atTime(11, 0)),
                TimeSlot(DATE.atTime(11, 0), DATE.atTime(11, 30)),
                TimeSlot(DATE.atTime(11, 30), DATE.atTime(12, 0)),
            ),
            slots,
        )
    }

    @Test
    fun `no available intervals produces no slots`() {
        val slots = TimeSlotEngine.generateSlots(
            date = DATE,
            availableIntervals = emptyList(),
            busyIntervals = emptyList(),
            serviceDurationMinutes = 30,
            slotIntervalMinutes = 30,
        )
        assertEquals(emptyList<TimeSlot>(), slots)
    }

    @Test
    fun `a fully booked window produces no slots`() {
        val slots = TimeSlotEngine.generateSlots(
            date = DATE,
            availableIntervals = listOf(t("09:00", "10:00")),
            busyIntervals = listOf(t("09:00", "10:00")),
            serviceDurationMinutes = 30,
            slotIntervalMinutes = 30,
        )
        assertEquals(emptyList<TimeSlot>(), slots)
    }

    @Test
    fun `earliestStart filters out slots that would start before it`() {
        val slots = TimeSlotEngine.generateSlots(
            date = DATE,
            availableIntervals = listOf(t("09:00", "12:00")),
            busyIntervals = emptyList(),
            serviceDurationMinutes = 30,
            slotIntervalMinutes = 30,
            earliestStart = LocalTime.of(10, 5),
        )
        assertEquals(DATE.atTime(10, 30), slots.first().start)
    }

    @Test
    fun `rejects a non-positive service duration`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeSlotEngine.generateSlots(DATE, listOf(t("09:00", "10:00")), emptyList(), 0, 15)
        }
    }

    @Test
    fun `rejects a non-positive slot interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeSlotEngine.generateSlots(DATE, listOf(t("09:00", "10:00")), emptyList(), 30, 0)
        }
    }
}
