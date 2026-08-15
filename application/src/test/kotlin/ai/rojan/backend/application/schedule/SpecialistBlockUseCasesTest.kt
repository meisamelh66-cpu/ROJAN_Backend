package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class SpecialistBlockUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon: Salon = salonRepository.save(Salon.create(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"))
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Jamie", null, null))

    private val createUseCase = CreateSpecialistBlockUseCase(specialistRepository, blockRepository, salonPermissionResolver)
    private val removeUseCase = RemoveSpecialistBlockUseCase(specialistRepository, blockRepository, salonPermissionResolver)

    private val interval = TimeInterval(LocalTime.of(14, 0), LocalTime.of(15, 0))

    @Test
    fun `owner can add an ad-hoc block`() {
        val block = createUseCase.execute(
            CreateSpecialistBlockCommand(specialist.id, owner, LocalDate.of(2026, 8, 10), interval, "Personal errand"),
        )
        assertEquals(interval, block.interval)
    }

    @Test
    fun `rejects adding a block from a caller who does not own the salon`() {
        assertThrows(SalonAccessDeniedException::class.java) {
            createUseCase.execute(CreateSpecialistBlockCommand(specialist.id, stranger, LocalDate.of(2026, 8, 10), interval, null))
        }
    }

    @Test
    fun `owner can remove a block`() {
        val block = createUseCase.execute(
            CreateSpecialistBlockCommand(specialist.id, owner, LocalDate.of(2026, 8, 10), interval, null),
        )

        removeUseCase.execute(RemoveSpecialistBlockCommand(block.id, owner))

        assertNull(blockRepository.findById(block.id))
    }
}
