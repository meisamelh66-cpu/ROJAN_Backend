package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpecialistUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"),
    )

    private val createUseCase = CreateSpecialistUseCase(salonRepository, specialistRepository, userRepository)
    private val updateUseCase = UpdateSpecialistUseCase(salonRepository, specialistRepository)
    private val deactivateUseCase = DeactivateSpecialistUseCase(salonRepository, specialistRepository)

    private fun createSpecialist() = createUseCase.execute(
        CreateSpecialistCommand(salon.id, owner, null, "Jamie Stylist", "10 years experience", null),
    )

    @Test
    fun `owner can add a standalone specialist without a user account`() {
        val specialist = createSpecialist()

        assertEquals(salon.id, specialist.salonId)
        assertNull(specialist.userId)
        assertEquals("Jamie Stylist", specialist.displayName)
    }

    @Test
    fun `owner can link a specialist to an existing user account`() {
        val staffUser = User.register(Email("staff@example.com"), "hash", "Staff Member", UserRole.SPECIALIST)
        userRepository.register(staffUser)

        val specialist = createUseCase.execute(
            CreateSpecialistCommand(salon.id, owner, staffUser.id, "Staff Member", null, null),
        )

        assertEquals(staffUser.id, specialist.userId)
    }

    @Test
    fun `rejects linking a specialist to a non-existent user`() {
        assertThrows<UserNotFoundException> {
            createUseCase.execute(CreateSpecialistCommand(salon.id, owner, UserId.new(), "Ghost", null, null))
        }
    }

    @Test
    fun `rejects adding a specialist to a salon the caller does not own`() {
        assertThrows<SalonAccessDeniedException> {
            createUseCase.execute(CreateSpecialistCommand(salon.id, stranger, null, "Intruder", null, null))
        }
    }

    @Test
    fun `owner can update a specialist`() {
        val specialist = createSpecialist()

        val updated = updateUseCase.execute(
            UpdateSpecialistCommand(specialist.id, owner, "Jamie Senior Stylist", "15 years experience", null),
        )

        assertEquals("Jamie Senior Stylist", updated.displayName)
    }

    @Test
    fun `update fails for an unknown specialist`() {
        assertThrows<SpecialistNotFoundException> {
            updateUseCase.execute(UpdateSpecialistCommand(SpecialistId.new(), owner, "Ghost", null, null))
        }
    }

    @Test
    fun `owner can deactivate a specialist`() {
        val specialist = createSpecialist()

        deactivateUseCase.execute(DeactivateSpecialistCommand(specialist.id, owner))

        assertFalse(specialistRepository.findById(specialist.id)!!.active)
    }

    @Test
    fun `rejects deactivating a specialist on a salon the caller does not own`() {
        val specialist = createSpecialist()

        assertThrows<SalonAccessDeniedException> {
            deactivateUseCase.execute(DeactivateSpecialistCommand(specialist.id, stranger))
        }
    }
}
