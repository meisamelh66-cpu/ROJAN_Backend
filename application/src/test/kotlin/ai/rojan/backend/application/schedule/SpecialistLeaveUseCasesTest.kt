package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SpecialistLeaveUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val leaveRepository = InMemoryLeaveRepository()
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon: Salon = salonRepository.save(Salon.create(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"))
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Jamie", null, null))

    private val createUseCase = CreateSpecialistLeaveUseCase(salonRepository, specialistRepository, leaveRepository)
    private val removeUseCase = RemoveSpecialistLeaveUseCase(salonRepository, specialistRepository, leaveRepository)

    @Test
    fun `owner can record a leave date range`() {
        val leave = createUseCase.execute(
            CreateSpecialistLeaveCommand(specialist.id, owner, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 15), "Vacation"),
        )
        assertEquals("Vacation", leave.reason)
    }

    @Test
    fun `rejects creating a leave from a caller who does not own the salon`() {
        assertThrows(SalonAccessDeniedException::class.java) {
            createUseCase.execute(CreateSpecialistLeaveCommand(specialist.id, stranger, LocalDate.now(), LocalDate.now(), null))
        }
    }

    @Test
    fun `owner can remove a leave record`() {
        val leave = createUseCase.execute(
            CreateSpecialistLeaveCommand(specialist.id, owner, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 15), null),
        )

        removeUseCase.execute(RemoveSpecialistLeaveCommand(leave.id, owner))

        assertNull(leaveRepository.findById(leave.id))
    }
}
