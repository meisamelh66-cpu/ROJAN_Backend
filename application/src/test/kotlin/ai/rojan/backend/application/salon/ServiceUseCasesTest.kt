package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.ServiceCategoryNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class ServiceUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val categoryRepository = InMemoryServiceCategoryRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"),
    )
    private val category = CreateServiceCategoryUseCase(salonRepository, categoryRepository).execute(
        CreateServiceCategoryCommand(salon.id, owner, "Hair", null),
    )

    private val createUseCase = CreateServiceUseCase(salonRepository, categoryRepository, serviceRepository)
    private val updateUseCase = UpdateServiceUseCase(salonRepository, serviceRepository)
    private val deactivateUseCase = DeactivateServiceUseCase(salonRepository, serviceRepository)

    private fun createService() = createUseCase.execute(
        CreateServiceCommand(
            salonId = salon.id,
            categoryId = category.id,
            callerId = owner,
            name = "Haircut",
            description = "Classic cut",
            durationMinutes = 30,
            price = BigDecimal("25.00"),
        ),
    )

    @Test
    fun `owner can add a service to a category`() {
        val service = createService()

        assertEquals(salon.id, service.salonId)
        assertEquals(category.id, service.categoryId)
        assertEquals(BigDecimal("25.00"), service.price)
    }

    @Test
    fun `rejects adding a service to a salon the caller does not own`() {
        assertThrows<SalonAccessDeniedException> {
            createUseCase.execute(
                CreateServiceCommand(salon.id, category.id, stranger, "Haircut", null, 30, BigDecimal("25.00")),
            )
        }
    }

    @Test
    fun `rejects a category that does not belong to the salon`() {
        assertThrows<ServiceCategoryNotFoundException> {
            createUseCase.execute(
                CreateServiceCommand(
                    salon.id,
                    ServiceCategoryId.new(),
                    owner,
                    "Haircut",
                    null,
                    30,
                    BigDecimal("25.00"),
                ),
            )
        }
    }

    @Test
    fun `owner can update a service`() {
        val service = createService()

        val updated = updateUseCase.execute(
            UpdateServiceCommand(service.id, owner, "Deluxe Haircut", "Includes wash", 45, BigDecimal("40.00")),
        )

        assertEquals("Deluxe Haircut", updated.name)
        assertEquals(45, updated.durationMinutes)
        assertEquals(BigDecimal("40.00"), updated.price)
    }

    @Test
    fun `update fails for an unknown service`() {
        assertThrows<ServiceNotFoundException> {
            updateUseCase.execute(UpdateServiceCommand(ServiceId.new(), owner, "Ghost", null, 30, BigDecimal("1.00")))
        }
    }

    @Test
    fun `owner can deactivate a service`() {
        val service = createService()

        deactivateUseCase.execute(DeactivateServiceCommand(service.id, owner))

        assertFalse(serviceRepository.findById(service.id)!!.active)
    }

    @Test
    fun `rejects deactivating a service on a salon the caller does not own`() {
        val service = createService()

        assertThrows<SalonAccessDeniedException> {
            deactivateUseCase.execute(DeactivateServiceCommand(service.id, stranger))
        }
    }
}
