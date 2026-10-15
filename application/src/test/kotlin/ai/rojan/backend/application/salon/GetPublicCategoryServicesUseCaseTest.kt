package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Issue-020 regression coverage: a deactivated category's services must not remain directly
 * fetchable by a known/guessed `categoryId`, even though its own, still-active service record is
 * untouched (no cascade, no data loss - a read-boundary guard only).
 */
class GetPublicCategoryServicesUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val categoryRepository = InMemoryServiceCategoryRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"),
    )
    private val otherSalon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Other Salon", null, "+1 555 0200", null, "2 Side St"),
    )
    private val category = CreateServiceCategoryUseCase(salonRepository, categoryRepository, salonPermissionResolver).execute(
        CreateServiceCategoryCommand(salon.id, owner, "Hair", null),
    )
    private val createServiceUseCase = CreateServiceUseCase(salonRepository, categoryRepository, serviceRepository, salonPermissionResolver)
    private val deactivateCategoryUseCase = DeactivateServiceCategoryUseCase(salonRepository, categoryRepository, salonPermissionResolver)
    private val useCase = GetPublicCategoryServicesUseCase(categoryRepository, serviceRepository)

    private fun createService(name: String = "Haircut") = createServiceUseCase.execute(
        CreateServiceCommand(salon.id, category.id, owner, name, null, 30, BigDecimal("25.00")),
    )

    @Test
    fun `an active category's active services are fetchable`() {
        val service = createService()

        val result = useCase.execute(salon.id, category.id)

        assertEquals(listOf(service.id), result.map { it.id })
    }

    @Test
    fun `a deactivated category returns no services at all - the real active service record is untouched`() {
        val service = createService()
        deactivateCategoryUseCase.execute(DeactivateServiceCategoryCommand(category.id, owner))

        val result = useCase.execute(salon.id, category.id)

        assertEquals(emptyList<Any>(), result)
        // The guard is read-only: the service itself was never deleted or deactivated.
        assertTrue(serviceRepository.findById(service.id)!!.active)
    }

    @Test
    fun `an inactive service within an active category is still excluded`() {
        val service = createService()
        val deactivateServiceUseCase = DeactivateServiceUseCase(salonRepository, serviceRepository, salonPermissionResolver)
        deactivateServiceUseCase.execute(DeactivateServiceCommand(service.id, owner))

        val result = useCase.execute(salon.id, category.id)

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `a categoryId belonging to a different salon returns no services, never another salon's data`() {
        createService()

        val result = useCase.execute(otherSalon.id, category.id)

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `an unknown categoryId returns an empty list, not an exception`() {
        val result = useCase.execute(salon.id, ServiceCategoryId.new())

        assertEquals(emptyList<Any>(), result)
    }
}
