package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SalonUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val createUseCase = CreateSalonUseCase(salonRepository)
    private val updateUseCase = UpdateSalonUseCase(salonRepository, salonPermissionResolver)
    private val deactivateUseCase = DeactivateSalonUseCase(salonRepository, salonPermissionResolver)

    private fun createSalon() = createUseCase.execute(
        CreateSalonCommand(
            ownerId = owner,
            name = "Glow Salon",
            description = "Full service salon",
            phone = "+1 555 0100",
            email = "hello@glow.example",
            address = "1 Main St",
        ),
    )

    @Test
    fun `creates a salon owned by the caller`() {
        val salon = createSalon()

        assertEquals(owner, salon.ownerId)
        assertEquals("Glow Salon", salon.name)
        assertEquals(salon, salonRepository.findById(salon.id))
    }

    @Test
    fun `owner can update their salon`() {
        val salon = createSalon()

        val updated = updateUseCase.execute(
            UpdateSalonCommand(
                salonId = salon.id,
                callerId = owner,
                name = "Glow Salon & Spa",
                description = null,
                phone = "+1 555 0199",
                email = null,
                address = "2 Main St",
            ),
        )

        assertEquals("Glow Salon & Spa", updated.name)
        assertEquals("2 Main St", updated.address)
    }

    @Test
    fun `rejects update from a caller who does not own the salon`() {
        val salon = createSalon()

        assertThrows<SalonAccessDeniedException> {
            updateUseCase.execute(
                UpdateSalonCommand(
                    salonId = salon.id,
                    callerId = stranger,
                    name = "Hijacked",
                    description = null,
                    phone = "+1 555 0199",
                    email = null,
                    address = "2 Main St",
                ),
            )
        }
    }

    @Test
    fun `update fails for an unknown salon`() {
        assertThrows<SalonNotFoundException> {
            updateUseCase.execute(
                UpdateSalonCommand(
                    salonId = SalonId.new(),
                    callerId = owner,
                    name = "Ghost",
                    description = null,
                    phone = "+1 555 0199",
                    email = null,
                    address = "Nowhere",
                ),
            )
        }
    }

    @Test
    fun `owner can deactivate their salon`() {
        val salon = createSalon()

        deactivateUseCase.execute(DeactivateSalonCommand(salon.id, owner))

        assertFalse(salonRepository.findById(salon.id)!!.active)
    }

    @Test
    fun `rejects deactivation from a caller who does not own the salon`() {
        val salon = createSalon()

        assertThrows<SalonAccessDeniedException> {
            deactivateUseCase.execute(DeactivateSalonCommand(salon.id, stranger))
        }
    }
}
