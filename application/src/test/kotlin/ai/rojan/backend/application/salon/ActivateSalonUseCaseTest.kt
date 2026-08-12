package ai.rojan.backend.application.salon

import ai.rojan.backend.application.schedule.InMemoryWorkingHoursRepository
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotReadyForActivationException
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime

class ActivateSalonUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val workingHoursRepository = InMemoryWorkingHoursRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val activateUseCase = ActivateSalonUseCase(
        salonRepository, serviceRepository, specialistRepository, workingHoursRepository, salonPermissionResolver,
    )

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", "Full service salon", "+1 555 0100", "hello@glow.example", "1 Main St"),
    )

    private fun addActiveService() {
        serviceRepository.save(Service.create(salon.id, ServiceCategoryId.new(), "Haircut", null, 30, BigDecimal.TEN))
    }

    private fun addActiveSpecialist() {
        specialistRepository.save(Specialist.create(salon.id, null, "Jordan Stylist", null, null))
    }

    private fun addWorkingHours() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
    }

    @Test
    fun `rejects activation when no active service exists`() {
        addActiveSpecialist()
        addWorkingHours()

        val ex = assertThrows<SalonNotReadyForActivationException> {
            activateUseCase.execute(ActivateSalonCommand(salon.id, owner))
        }
        assertTrue(ex.message!!.contains("active service"))
    }

    @Test
    fun `rejects activation when no active specialist exists`() {
        addActiveService()
        addWorkingHours()

        val ex = assertThrows<SalonNotReadyForActivationException> {
            activateUseCase.execute(ActivateSalonCommand(salon.id, owner))
        }
        assertTrue(ex.message!!.contains("active specialist"))
    }

    @Test
    fun `rejects activation when no working hours are configured`() {
        addActiveService()
        addActiveSpecialist()

        val ex = assertThrows<SalonNotReadyForActivationException> {
            activateUseCase.execute(ActivateSalonCommand(salon.id, owner))
        }
        assertTrue(ex.message!!.contains("working-hours"))
    }

    @Test
    fun `activates once service, specialist, and working hours are all present`() {
        addActiveService()
        addActiveSpecialist()
        addWorkingHours()

        val activated = activateUseCase.execute(ActivateSalonCommand(salon.id, owner))

        assertEquals(ai.rojan.backend.domain.salon.SalonOnboardingStatus.ACTIVE, activated.onboardingStatus)
    }

    @Test
    fun `rejects activation from a caller who does not own the salon`() {
        addActiveService()
        addActiveSpecialist()
        addWorkingHours()

        assertThrows<SalonAccessDeniedException> {
            activateUseCase.execute(ActivateSalonCommand(salon.id, stranger))
        }
    }
}
