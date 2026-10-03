package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Admin Salon Visibility (owner inspection): proves [GetPlatformManagerUseCase] - the single-account
 * lookup a salon detail page's own "owner" section needs, since [ListPlatformManagersUseCase] only
 * text-searches by name/phone, never by the real [ai.rojan.backend.domain.salon.Salon.ownerId] a
 * salon detail page already has in hand - resolves the same real salon associations
 * [ListPlatformManagersUseCase] computes per-row, for PLATFORM_ADMIN or PLATFORM_REVIEWER.
 */
class GetPlatformManagerUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val salonRepository = InMemorySalonRepository()
    private val salonMembershipRepository = InMemorySalonMembershipRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val useCase = GetPlatformManagerUseCase(userRepository, salonRepository, salonMembershipRepository, platformAuthorization)

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555044${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Platform Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val reviewer = User.registerWithPhone(nextPhone(), "Platform Reviewer", UserRole.PLATFORM_REVIEWER).also { userRepository.save(it) }
    private val customer = User.registerWithPhone(nextPhone(), "Just a Customer", UserRole.CUSTOMER).also { userRepository.save(it) }
    private val owner = User.registerWithPhone(nextPhone(), "Sara Ahmadi", UserRole.MANAGER).also { userRepository.save(it) }

    private fun salon(owned: User = owner): Salon =
        Salon.create(
            ownerId = owned.id,
            name = "Owned Salon",
            description = null,
            phone = "+15550100",
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.ACTIVE,
        ).also { salonRepository.save(it) }

    @Test
    fun `PLATFORM_ADMIN can look up a manager by id and sees their real owned-salon association`() {
        val owned = salon()

        val result = useCase.execute(GetPlatformManagerQuery(admin.id, owner.id))

        assertEquals(owner.id, result.user.id)
        assertEquals("Sara Ahmadi", result.user.fullName)
        assertTrue(result.salonAssociations.any { it.salonId == owned.id && it.accessType == ManagerSalonAccessType.OWNER })
    }

    @Test
    fun `PLATFORM_REVIEWER can also look up a manager - read access is shared with PLATFORM_ADMIN`() {
        salon()

        val result = useCase.execute(GetPlatformManagerQuery(reviewer.id, owner.id))

        assertEquals(owner.id, result.user.id)
    }

    @Test
    fun `a CUSTOMER cannot look up manager accounts`() {
        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(GetPlatformManagerQuery(customer.id, owner.id))
        }
    }

    @Test
    fun `looking up a non-MANAGER user id, e g a CUSTOMER, 404s identically to a truly unknown id`() {
        assertThrows<UserNotFoundException> {
            useCase.execute(GetPlatformManagerQuery(admin.id, customer.id))
        }
    }

    @Test
    fun `also includes real salon associations reached via membership, not just ownership`() {
        val ownedByOther = salon(owned = User.registerWithPhone(nextPhone(), "Other Owner", UserRole.MANAGER).also { userRepository.save(it) })
        salonMembershipRepository.assign(ownedByOther.id, owner.id, SalonRole.RECEPTIONIST)

        val result = useCase.execute(GetPlatformManagerQuery(admin.id, owner.id))

        val membership = result.salonAssociations.single { it.salonId == ownedByOther.id }
        assertEquals(ManagerSalonAccessType.MEMBER, membership.accessType)
        assertEquals(SalonRole.RECEPTIONIST, membership.role)
    }
}
