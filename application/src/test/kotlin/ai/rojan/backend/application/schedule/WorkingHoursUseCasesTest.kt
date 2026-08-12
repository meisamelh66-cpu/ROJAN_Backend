package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

class WorkingHoursUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val workingHoursRepository = InMemoryWorkingHoursRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon: Salon = salonRepository.save(
        Salon.create(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"),
    )

    private val setUseCase = SetWorkingHoursUseCase(salonRepository, workingHoursRepository, salonPermissionResolver)
    private val removeUseCase = RemoveWorkingHoursUseCase(salonRepository, workingHoursRepository, salonPermissionResolver)

    private val mondayIntervals = listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))

    @Test
    fun `owner can set working hours for a day`() {
        val result = setUseCase.execute(SetWorkingHoursCommand(salon.id, owner, DayOfWeek.MONDAY, mondayIntervals))

        assertEquals(mondayIntervals, result.intervals)
        assertEquals(salon.id, workingHoursRepository.findBySalonIdAndDayOfWeek(salon.id, DayOfWeek.MONDAY)?.salonId)
    }

    @Test
    fun `setting the same day again updates it in place rather than duplicating`() {
        val first = setUseCase.execute(SetWorkingHoursCommand(salon.id, owner, DayOfWeek.MONDAY, mondayIntervals))
        val newIntervals = listOf(TimeInterval(LocalTime.of(10, 0), LocalTime.of(18, 0)))
        val second = setUseCase.execute(SetWorkingHoursCommand(salon.id, owner, DayOfWeek.MONDAY, newIntervals))

        assertEquals(first.id, second.id)
        assertEquals(newIntervals, second.intervals)
    }

    @Test
    fun `rejects setting hours from a caller who does not own the salon`() {
        assertThrows(SalonAccessDeniedException::class.java) {
            setUseCase.execute(SetWorkingHoursCommand(salon.id, stranger, DayOfWeek.MONDAY, mondayIntervals))
        }
    }

    @Test
    fun `set fails for an unknown salon`() {
        assertThrows(SalonNotFoundException::class.java) {
            setUseCase.execute(SetWorkingHoursCommand(SalonId.new(), owner, DayOfWeek.MONDAY, mondayIntervals))
        }
    }

    @Test
    fun `owner can remove working hours for a day, marking the salon closed`() {
        setUseCase.execute(SetWorkingHoursCommand(salon.id, owner, DayOfWeek.MONDAY, mondayIntervals))

        removeUseCase.execute(RemoveWorkingHoursCommand(salon.id, owner, DayOfWeek.MONDAY))

        assertNull(workingHoursRepository.findBySalonIdAndDayOfWeek(salon.id, DayOfWeek.MONDAY))
    }

    @Test
    fun `rejects removing hours from a caller who does not own the salon`() {
        setUseCase.execute(SetWorkingHoursCommand(salon.id, owner, DayOfWeek.MONDAY, mondayIntervals))

        assertThrows(SalonAccessDeniedException::class.java) {
            removeUseCase.execute(RemoveWorkingHoursCommand(salon.id, stranger, DayOfWeek.MONDAY))
        }
    }
}
