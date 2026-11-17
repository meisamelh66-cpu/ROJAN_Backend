package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Staff Access Phase 1, item 2 - the dedicated staff-candidate phone lookup, deliberately separate
 * from [ai.rojan.backend.application.customer.LookupUserForCustomerLinkUseCase] (see that use
 * case's own doc comment and this one's). Covers the exact behaviors the spec calls out: existing
 * user found, no-account response, malformed phone, cross-tenant isolation, and that it can't be
 * used to enumerate which phone numbers have (deactivated) accounts.
 */
class LookupStaffCandidateUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val useCase = LookupStaffCandidateUseCase(salonRepository, userRepository, salonPermissionResolver)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    private fun newUser(phone: String, active: Boolean = true) =
        User.registerWithPhone(PhoneNumber(phone), "Candidate Person", UserRole.CUSTOMER)
            .also { if (!active) it.deactivate() }
            .also { userRepository.register(it) }

    @Test
    fun `resolves an existing active user by phone number`() {
        val user = newUser("+989120000201")

        val candidate = useCase.execute(LookupStaffCandidateCommand(salon.id, ownerId, "+989120000201"))

        assertEquals(user.id, candidate.userId)
        assertEquals(user.fullName, candidate.fullName)
        assertEquals("+989120000201", candidate.phoneNumber)
    }

    @Test
    fun `returns not-found when no account matches the phone number`() {
        assertThrows<UserNotFoundException> {
            useCase.execute(LookupStaffCandidateCommand(salon.id, ownerId, "+989120000202"))
        }
    }

    @Test
    fun `treats a matching but inactive account exactly like no match - never discloses it exists`() {
        newUser("+989120000203", active = false)

        assertThrows<UserNotFoundException> {
            useCase.execute(LookupStaffCandidateCommand(salon.id, ownerId, "+989120000203"))
        }
    }

    @Test
    fun `rejects a malformed phone number as a validation error, not a lookup miss`() {
        assertThrows<IllegalArgumentException> {
            useCase.execute(LookupStaffCandidateCommand(salon.id, ownerId, "not-a-phone"))
        }
    }

    @Test
    fun `rejects a caller without MANAGE_MEMBERSHIP at this salon`() {
        newUser("+989120000204")

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(LookupStaffCandidateCommand(salon.id, UserId.new(), "+989120000204"))
        }
    }

    @Test
    fun `a salon owner cannot use their own permission to look up candidates for a salon they don't own`() {
        val otherOwnerId = UserId.new()
        val otherSalon = Salon.create(otherOwnerId, "Other Salon", null, "0913", null, "Address").also { salonRepository.save(it) }
        newUser("+989120000205")

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(LookupStaffCandidateCommand(otherSalon.id, ownerId, "+989120000205"))
        }
    }
}
