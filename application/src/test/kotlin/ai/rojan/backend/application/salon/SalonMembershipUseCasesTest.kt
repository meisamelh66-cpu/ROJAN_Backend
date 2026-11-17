package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.InvalidMembershipAssignmentException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonMembershipNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Staff Access Phase 1 - the backend contract was real and shipped (owner-only direct role
 * assignment) but had zero test coverage before this phase. Covers assign/duplicate-reassign,
 * disable/re-enable (the new active-state work), role change, true removal, and the
 * cross-tenant/unauthorized boundaries - including the critical security property that a
 * disabled membership grants zero permissions via [SalonPermissionResolver].
 */
class SalonMembershipUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)

    private val assignUseCase = AssignMembershipUseCase(salonRepository, userRepository, membershipRepository, salonPermissionResolver)
    private val removeUseCase = RemoveMembershipUseCase(salonRepository, membershipRepository, salonPermissionResolver)
    private val setActiveUseCase = SetMembershipActiveUseCase(salonRepository, membershipRepository, salonPermissionResolver)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    private fun newStaffUser(phone: String) =
        User.registerWithPhone(PhoneNumber(phone), "Staff Member", UserRole.CUSTOMER).also { userRepository.register(it) }

    @Test
    fun `assigns a role to an existing user and grants that role's permissions`() {
        val staff = newStaffUser("+989120000101")

        val membership = assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))

        assertEquals(SalonRole.RECEPTIONIST, membership.role)
        assertTrue(membership.active)
        assertEquals(SalonRole.RECEPTIONIST.permissions(), salonPermissionResolver.resolve(salon.id, staff.id))
    }

    @Test
    fun `reassigning a role upserts in place rather than creating a duplicate membership`() {
        val staff = newStaffUser("+989120000102")

        val first = assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))
        val second = assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.MANAGER))

        assertEquals(first.id, second.id)
        assertEquals(SalonRole.MANAGER, second.role)
        assertEquals(1, membershipRepository.findBySalonId(salon.id).size)
    }

    @Test
    fun `reassigning a role re-activates a previously disabled membership`() {
        val staff = newStaffUser("+989120000103")
        assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))
        setActiveUseCase.execute(SetMembershipActiveCommand(salon.id, ownerId, staff.id, active = false))

        val reassigned = assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))

        assertTrue(reassigned.active)
    }

    @Test
    fun `rejects assigning a role to the salon's own owner`() {
        assertThrows<InvalidMembershipAssignmentException> {
            assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, ownerId, SalonRole.MANAGER))
        }
    }

    @Test
    fun `rejects assigning a role to a phone number with no ROJAN account`() {
        assertThrows<UserNotFoundException> {
            assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, UserId.new(), SalonRole.RECEPTIONIST))
        }
    }

    @Test
    fun `rejects a caller without MANAGE_MEMBERSHIP at this salon`() {
        val staff = newStaffUser("+989120000104")

        assertThrows<SalonAccessDeniedException> {
            assignUseCase.execute(AssignMembershipCommand(salon.id, UserId.new(), staff.id, SalonRole.RECEPTIONIST))
        }
    }

    @Test
    fun `a manager cannot manage membership at a salon they do not own`() {
        val otherOwnerId = UserId.new()
        val otherSalon = Salon.create(otherOwnerId, "Other Salon", null, "0913", null, "Address").also { salonRepository.save(it) }
        val manager = newStaffUser("+989120000105")
        assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, manager.id, SalonRole.MANAGER))

        assertThrows<SalonAccessDeniedException> {
            assignUseCase.execute(AssignMembershipCommand(otherSalon.id, manager.id, newStaffUser("+989120000106").id, SalonRole.RECEPTIONIST))
        }
    }

    @Test
    fun `disabling a membership keeps the row and role but grants zero permissions`() {
        val staff = newStaffUser("+989120000107")
        assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))

        val disabled = setActiveUseCase.execute(SetMembershipActiveCommand(salon.id, ownerId, staff.id, active = false))

        assertEquals(false, disabled.active)
        assertEquals(SalonRole.RECEPTIONIST, disabled.role)
        assertEquals(emptySet<Permission>(), salonPermissionResolver.resolve(salon.id, staff.id))
        assertThrows<SalonAccessDeniedException> {
            salonPermissionResolver.require(salon.id, staff.id, Permission.MANAGE_BOOKINGS)
        }
    }

    @Test
    fun `re-enabling a disabled membership restores its role's permissions without recreating it`() {
        val staff = newStaffUser("+989120000108")
        val original = assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))
        setActiveUseCase.execute(SetMembershipActiveCommand(salon.id, ownerId, staff.id, active = false))

        val reEnabled = setActiveUseCase.execute(SetMembershipActiveCommand(salon.id, ownerId, staff.id, active = true))

        assertEquals(original.id, reEnabled.id)
        assertTrue(reEnabled.active)
        assertEquals(SalonRole.RECEPTIONIST.permissions(), salonPermissionResolver.resolve(salon.id, staff.id))
    }

    @Test
    fun `disabling a membership that doesn't exist throws a dedicated not-found error`() {
        assertThrows<SalonMembershipNotFoundException> {
            setActiveUseCase.execute(SetMembershipActiveCommand(salon.id, ownerId, UserId.new(), active = false))
        }
    }

    @Test
    fun `changing role is reflected immediately in resolved permissions`() {
        val staff = newStaffUser("+989120000109")
        assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))
        assertEquals(SalonRole.RECEPTIONIST.permissions(), salonPermissionResolver.resolve(salon.id, staff.id))

        assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.MANAGER))

        assertEquals(SalonRole.MANAGER.permissions(), salonPermissionResolver.resolve(salon.id, staff.id))
    }

    @Test
    fun `true removal deletes the row entirely - distinct from disabling`() {
        val staff = newStaffUser("+989120000110")
        assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))

        removeUseCase.execute(RemoveMembershipCommand(salon.id, ownerId, staff.id))

        assertEquals(null, membershipRepository.findBySalonIdAndUserId(salon.id, staff.id))
        assertThrows<SalonMembershipNotFoundException> {
            setActiveUseCase.execute(SetMembershipActiveCommand(salon.id, ownerId, staff.id, active = false))
        }
    }

    @Test
    fun `a staff member can never self-escalate by assigning their own membership`() {
        val staff = newStaffUser("+989120000111")
        assignUseCase.execute(AssignMembershipCommand(salon.id, ownerId, staff.id, SalonRole.RECEPTIONIST))

        assertThrows<SalonAccessDeniedException> {
            assignUseCase.execute(AssignMembershipCommand(salon.id, staff.id, staff.id, SalonRole.MANAGER))
        }
    }
}
