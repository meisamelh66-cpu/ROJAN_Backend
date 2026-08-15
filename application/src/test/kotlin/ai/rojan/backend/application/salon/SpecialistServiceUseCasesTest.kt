package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class SpecialistServiceUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val categoryRepository = InMemoryServiceCategoryRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val specialistServiceRepository = InMemorySpecialistServiceRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)

    private val owner = UserId.new()
    private val salon: Salon = salonRepository.save(Salon.create(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"))
    private val category: ServiceCategory = categoryRepository.save(ServiceCategory.create(salon.id, "Hair", null))
    private val service: Service = serviceRepository.save(Service.create(salon.id, category.id, "Haircut", null, 30, BigDecimal("25.00")))
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Jamie", null, null))

    private val assignUseCase = AssignServiceToSpecialistUseCase(specialistRepository, serviceRepository, specialistServiceRepository, salonPermissionResolver)
    private val removeUseCase = RemoveServiceFromSpecialistUseCase(specialistRepository, serviceRepository, specialistServiceRepository, salonPermissionResolver)

    @Test
    fun `owner can assign a service to a specialist`() {
        assignUseCase.execute(AssignServiceToSpecialistCommand(salon.id, specialist.id, service.id, owner))
        assertTrue(service.id in specialistServiceRepository.findServiceIdsBySpecialistId(specialist.id))
    }

    @Test
    fun `owner can remove a previously assigned service`() {
        assignUseCase.execute(AssignServiceToSpecialistCommand(salon.id, specialist.id, service.id, owner))
        removeUseCase.execute(RemoveServiceFromSpecialistCommand(salon.id, specialist.id, service.id, owner))
        assertEquals(emptySet<Any>(), specialistServiceRepository.findServiceIdsBySpecialistId(specialist.id))
    }

    @Test
    fun `rejects assignment from a caller who does not own the salon`() {
        assertThrows<SalonAccessDeniedException> {
            assignUseCase.execute(AssignServiceToSpecialistCommand(salon.id, specialist.id, service.id, UserId.new()))
        }
    }

    @Test
    fun `rejects assigning a service that belongs to a different salon - tenant isolation`() {
        val otherOwner = UserId.new()
        val otherSalon = salonRepository.save(Salon.create(otherOwner, "Other Salon", null, "+1 555 0200", null, "2 Other St"))
        val otherCategory = categoryRepository.save(ServiceCategory.create(otherSalon.id, "Nails", null))
        val otherSalonService = serviceRepository.save(Service.create(otherSalon.id, otherCategory.id, "Manicure", null, 30, BigDecimal("15.00")))

        assertThrows<ServiceNotFoundException> {
            assignUseCase.execute(AssignServiceToSpecialistCommand(salon.id, specialist.id, otherSalonService.id, owner))
        }
    }

    @Test
    fun `rejects assigning to a specialist that belongs to a different salon - tenant isolation`() {
        val otherOwner = UserId.new()
        val otherSalon = salonRepository.save(Salon.create(otherOwner, "Other Salon", null, "+1 555 0200", null, "2 Other St"))
        val otherSalonSpecialist = specialistRepository.save(Specialist.create(otherSalon.id, null, "Alex", null, null))

        assertThrows<SpecialistNotFoundException> {
            assignUseCase.execute(AssignServiceToSpecialistCommand(salon.id, otherSalonSpecialist.id, service.id, owner))
        }
    }
}
