package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

class SpecialistWeeklyAvailabilityUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val availabilityRepository = InMemoryWeeklyAvailabilityRepository()
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon: Salon = salonRepository.save(Salon.create(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"))
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Jamie", null, null))

    private val setUseCase = SetSpecialistWeeklyAvailabilityUseCase(salonRepository, specialistRepository, availabilityRepository)
    private val removeUseCase = RemoveSpecialistWeeklyAvailabilityUseCase(salonRepository, specialistRepository, availabilityRepository)

    private val intervals = listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))

    @Test
    fun `owner can set a specialist's weekly availability`() {
        val result = setUseCase.execute(SetWeeklyAvailabilityCommand(specialist.id, owner, DayOfWeek.TUESDAY, intervals))
        assertEquals(intervals, result.intervals)
    }

    @Test
    fun `rejects setting availability from a caller who does not own the salon`() {
        assertThrows(SalonAccessDeniedException::class.java) {
            setUseCase.execute(SetWeeklyAvailabilityCommand(specialist.id, stranger, DayOfWeek.TUESDAY, intervals))
        }
    }

    @Test
    fun `owner can remove a specialist's weekly availability for a day`() {
        setUseCase.execute(SetWeeklyAvailabilityCommand(specialist.id, owner, DayOfWeek.TUESDAY, intervals))

        removeUseCase.execute(RemoveWeeklyAvailabilityCommand(specialist.id, owner, DayOfWeek.TUESDAY))

        assertNull(availabilityRepository.findBySpecialistIdAndDayOfWeek(specialist.id, DayOfWeek.TUESDAY))
    }
}
