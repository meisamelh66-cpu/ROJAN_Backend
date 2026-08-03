package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.ScheduleOverrideNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.schedule.ScheduleOverrideId
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleOverrideUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val overrideRepository = InMemoryScheduleOverrideRepository()
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon: Salon = salonRepository.save(Salon.create(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"))
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Jamie", null, null))

    private val setUseCase = SetScheduleOverrideUseCase(salonRepository, specialistRepository, overrideRepository)
    private val removeUseCase = RemoveScheduleOverrideUseCase(salonRepository, specialistRepository, overrideRepository)

    private val date = LocalDate.of(2026, 12, 25)

    @Test
    fun `owner can set an override with reduced hours for a date`() {
        val intervals = listOf(TimeInterval(LocalTime.of(10, 0), LocalTime.of(14, 0)))
        val result = setUseCase.execute(SetScheduleOverrideCommand(specialist.id, owner, date, intervals, "Holiday hours"))

        assertEquals(intervals, result.intervals)
        assertEquals("Holiday hours", result.reason)
    }

    @Test
    fun `owner can set an override with no intervals to mark a full day off`() {
        val result = setUseCase.execute(SetScheduleOverrideCommand(specialist.id, owner, date, emptyList(), "Day off"))
        assertTrue(result.intervals.isEmpty())
    }

    @Test
    fun `setting an override for the same date again replaces it rather than duplicating`() {
        val first = setUseCase.execute(SetScheduleOverrideCommand(specialist.id, owner, date, emptyList(), null))
        val second = setUseCase.execute(
            SetScheduleOverrideCommand(specialist.id, owner, date, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(12, 0))), null),
        )

        assertEquals(first.id, second.id)
        assertEquals(1, second.intervals.size)
    }

    @Test
    fun `rejects setting an override from a caller who does not own the salon`() {
        assertThrows(SalonAccessDeniedException::class.java) {
            setUseCase.execute(SetScheduleOverrideCommand(specialist.id, stranger, date, emptyList(), null))
        }
    }

    @Test
    fun `owner can remove an override`() {
        val override = setUseCase.execute(SetScheduleOverrideCommand(specialist.id, owner, date, emptyList(), null))

        removeUseCase.execute(RemoveScheduleOverrideCommand(override.id, owner))

        assertNull(overrideRepository.findById(override.id))
    }

    @Test
    fun `remove fails for an unknown override`() {
        assertThrows(ScheduleOverrideNotFoundException::class.java) {
            removeUseCase.execute(RemoveScheduleOverrideCommand(ScheduleOverrideId.new(), owner))
        }
    }
}
