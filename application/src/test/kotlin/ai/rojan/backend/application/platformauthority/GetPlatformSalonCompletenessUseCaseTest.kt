package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.schedule.InMemoryWorkingHoursRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Admin Salon Edit read side: the platform-authorized equivalent of
 * `GET /api/v1/salons/{salonId}/completeness`, which 403s for any caller who isn't a salon member
 * (`SalonPermissionResolver.require(..., MANAGE_SALON)`) - a platform admin/reviewer never is.
 * Proves (1) PLATFORM_ADMIN and PLATFORM_REVIEWER can both read it (no mutation, nothing to differ
 * on), (2) a MANAGER who isn't platform staff is still rejected even though they'd normally pass
 * the owner-scoped route, (3) every real completeness field reaches the caller including the
 * null/false distinction, (4) `missingForActivation` matches the same three-requirement gate
 * [ai.rojan.backend.application.salon.ActivateSalonUseCase] itself enforces.
 */
class GetPlatformSalonCompletenessUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val salonRepository = InMemorySalonRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val workingHoursRepository = InMemoryWorkingHoursRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val useCase = GetPlatformSalonCompletenessUseCase(
        salonRepository,
        serviceRepository,
        specialistRepository,
        workingHoursRepository,
        platformAuthorization,
    )

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555042${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Platform Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val reviewer = User.registerWithPhone(nextPhone(), "Platform Reviewer", UserRole.PLATFORM_REVIEWER).also { userRepository.save(it) }
    private val manager = User.registerWithPhone(nextPhone(), "Salon Manager", UserRole.MANAGER).also { userRepository.save(it) }

    private fun salon(): Salon =
        Salon.create(
            ownerId = manager.id,
            name = "Test Salon",
            description = null,
            phone = "+15550100",
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.DRAFT,
        ).also { salonRepository.save(it) }

    @Test
    fun `PLATFORM_ADMIN can read a salon's completeness even though they are not a member`() {
        val target = salon()

        val result = useCase.execute(GetPlatformSalonCompletenessQuery(admin.id, target.id))

        assertEquals(target.id, result.salon.id)
    }

    @Test
    fun `PLATFORM_REVIEWER can read it too - this is a read-only query`() {
        val target = salon()

        val result = useCase.execute(GetPlatformSalonCompletenessQuery(reviewer.id, target.id))

        assertEquals(target.id, result.salon.id)
    }

    @Test
    fun `a MANAGER - even this salon's own owner - cannot read it through the platform route`() {
        val target = salon()

        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(GetPlatformSalonCompletenessQuery(manager.id, target.id))
        }
    }

    @Test
    fun `an unknown salon id throws SalonNotFoundException`() {
        assertThrows<SalonNotFoundException> {
            useCase.execute(GetPlatformSalonCompletenessQuery(admin.id, SalonId.new()))
        }
    }

    @Test
    fun `every real completeness field reaches the caller, preserving the null vs false distinction`() {
        val target = salon()
        target.updateCompletionProfile(
            activityStartJalaliYear = 1398,
            hasInternalExtensions = true,
            sellsProducts = null,
            hasCafe = false,
            hasStaffUniform = true,
            isNeighborhoodSalon = null,
            isCityCenterSalon = false,
            primaryContactMembershipId = null,
        )
        salonRepository.save(target)

        val result = useCase.execute(GetPlatformSalonCompletenessQuery(admin.id, target.id))

        assertEquals(1398, result.salon.activityStartJalaliYear)
        assertTrue(result.salon.hasInternalExtensions)
        assertNull(result.salon.sellsProducts)
        assertFalse(result.salon.hasCafe!!)
        assertTrue(result.salon.hasStaffUniform!!)
        assertNull(result.salon.isNeighborhoodSalon)
        assertFalse(result.salon.isCityCenterSalon!!)
    }

    @Test
    fun `missingForActivation reports all three requirements for a brand-new salon`() {
        val target = salon()

        val result = useCase.execute(GetPlatformSalonCompletenessQuery(admin.id, target.id))

        assertEquals(3, result.missingForActivation.size)
    }
}
