package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.ServiceCategoryNotFoundException
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServiceCategoryUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val categoryRepository = InMemoryServiceCategoryRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"),
    )

    private val createUseCase = CreateServiceCategoryUseCase(salonRepository, categoryRepository, salonPermissionResolver)
    private val updateUseCase = UpdateServiceCategoryUseCase(salonRepository, categoryRepository, salonPermissionResolver)
    private val deactivateUseCase = DeactivateServiceCategoryUseCase(salonRepository, categoryRepository, salonPermissionResolver)

    private fun createCategory() = createUseCase.execute(
        CreateServiceCategoryCommand(salon.id, owner, "Hair", "Cuts and styling"),
    )

    @Test
    fun `owner can add a service category to their salon`() {
        val category = createCategory()

        assertEquals(salon.id, category.salonId)
        assertEquals("Hair", category.name)
    }

    @Test
    fun `rejects adding a category to a salon the caller does not own`() {
        assertThrows<SalonAccessDeniedException> {
            createUseCase.execute(CreateServiceCategoryCommand(salon.id, stranger, "Nails", null))
        }
    }

    @Test
    fun `owner can update a service category`() {
        val category = createCategory()

        val updated = updateUseCase.execute(
            UpdateServiceCategoryCommand(category.id, owner, "Hair & Beauty", "Updated description"),
        )

        assertEquals("Hair & Beauty", updated.name)
    }

    @Test
    fun `update fails for an unknown category`() {
        assertThrows<ServiceCategoryNotFoundException> {
            updateUseCase.execute(UpdateServiceCategoryCommand(ServiceCategoryId.new(), owner, "Ghost", null))
        }
    }

    @Test
    fun `owner can deactivate a service category`() {
        val category = createCategory()

        deactivateUseCase.execute(DeactivateServiceCategoryCommand(category.id, owner))

        assertFalse(categoryRepository.findById(category.id)!!.active)
    }

    @Test
    fun `rejects deactivating a category on a salon the caller does not own`() {
        val category = createCategory()

        assertThrows<SalonAccessDeniedException> {
            deactivateUseCase.execute(DeactivateServiceCategoryCommand(category.id, stranger))
        }
    }
}
