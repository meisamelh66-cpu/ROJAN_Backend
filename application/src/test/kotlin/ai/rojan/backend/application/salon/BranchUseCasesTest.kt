package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.BranchNotFoundException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.salon.BranchId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BranchUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val branchRepository = InMemoryBranchRepository()
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"),
    )

    private val createUseCase = CreateBranchUseCase(salonRepository, branchRepository)
    private val updateUseCase = UpdateBranchUseCase(salonRepository, branchRepository)
    private val deactivateUseCase = DeactivateBranchUseCase(salonRepository, branchRepository)

    private fun createBranch() = createUseCase.execute(
        CreateBranchCommand(salon.id, owner, "Downtown", "10 Center Ave", "+1 555 0111"),
    )

    @Test
    fun `owner can add a branch to their salon`() {
        val branch = createBranch()

        assertEquals(salon.id, branch.salonId)
        assertEquals("Downtown", branch.name)
    }

    @Test
    fun `rejects adding a branch to a salon the caller does not own`() {
        assertThrows<SalonAccessDeniedException> {
            createUseCase.execute(CreateBranchCommand(salon.id, stranger, "Uptown", "20 North Ave", "+1 555 0112"))
        }
    }

    @Test
    fun `owner can update a branch`() {
        val branch = createBranch()

        val updated = updateUseCase.execute(
            UpdateBranchCommand(branch.id, owner, "Downtown Flagship", "11 Center Ave", "+1 555 0113"),
        )

        assertEquals("Downtown Flagship", updated.name)
    }

    @Test
    fun `update fails for an unknown branch`() {
        assertThrows<BranchNotFoundException> {
            updateUseCase.execute(UpdateBranchCommand(BranchId.new(), owner, "Ghost", "Nowhere", "+1 555 0000"))
        }
    }

    @Test
    fun `owner can deactivate a branch`() {
        val branch = createBranch()

        deactivateUseCase.execute(DeactivateBranchCommand(branch.id, owner))

        assertFalse(branchRepository.findById(branch.id)!!.active)
    }

    @Test
    fun `rejects deactivating a branch on a salon the caller does not own`() {
        val branch = createBranch()

        assertThrows<SalonAccessDeniedException> {
            deactivateUseCase.execute(DeactivateBranchCommand(branch.id, stranger))
        }
    }
}
