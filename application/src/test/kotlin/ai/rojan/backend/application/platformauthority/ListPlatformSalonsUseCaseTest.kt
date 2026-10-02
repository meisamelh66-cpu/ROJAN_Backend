package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Admin Salon Visibility: the real gap this use case closes was that `GET /api/v1/salons`
 * (`SalonController.list`, backed by [ai.rojan.backend.domain.salon.SalonRepository.findAllActive])
 * can never return a DRAFT or deactivated salon - confirmed by that method's own doc comment. These
 * tests exercise the new, separate [ListPlatformSalonsUseCase] directly against a real (in-memory)
 * [ai.rojan.backend.domain.salon.SalonRepository], proving it returns every status, while every
 * existing [InMemorySalonRepository.findAllActive]/`findAllPubliclyDiscoverable` test elsewhere in
 * this suite is left completely unchanged and still passes - this use case adds a new read path, it
 * never touches the customer-facing ones.
 */
class ListPlatformSalonsUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val salonRepository = InMemorySalonRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val useCase = ListPlatformSalonsUseCase(salonRepository, platformAuthorization)

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555040${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Platform Admin", UserRole.PLATFORM_ADMIN)
        .also { userRepository.save(it) }
    private val reviewer = User.registerWithPhone(nextPhone(), "Platform Reviewer", UserRole.PLATFORM_REVIEWER)
        .also { userRepository.save(it) }
    private val manager = User.registerWithPhone(nextPhone(), "Salon Manager", UserRole.MANAGER)
        .also { userRepository.save(it) }
    private val customer = User.registerWithPhone(nextPhone(), "Just a Customer", UserRole.CUSTOMER)
        .also { userRepository.save(it) }

    private fun draftSalon(name: String, ownerId: UserId = manager.id): Salon =
        Salon.create(
            ownerId = ownerId,
            name = name,
            description = null,
            phone = "+15550100",
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.DRAFT,
        ).also { salonRepository.save(it) }

    private fun activeSalon(name: String, ownerId: UserId = manager.id): Salon =
        Salon.create(
            ownerId = ownerId,
            name = name,
            description = null,
            phone = "+15550100",
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.ACTIVE,
        ).also { salonRepository.save(it) }

    @Test
    fun `a PLATFORM_ADMIN can list a DRAFT salon with its real onboardingStatus and active fields`() {
        val draft = draftSalon("Fresh Draft Salon")

        val result = useCase.execute(ListPlatformSalonsQuery(admin.id, 0, 20, null, SortDirection.ASC))

        val found = result.content.find { it.id == draft.id }
        assertTrue(found != null, "the DRAFT salon must be visible to a platform admin")
        assertEquals(SalonOnboardingStatus.DRAFT, found!!.onboardingStatus)
        assertTrue(found.active, "a freshly created salon is still active=true, only onboardingStatus is DRAFT")
    }

    @Test
    fun `a PLATFORM_REVIEWER can also list a DRAFT salon - read access is shared with PLATFORM_ADMIN`() {
        val draft = draftSalon("Reviewer Visible Draft")

        val result = useCase.execute(ListPlatformSalonsQuery(reviewer.id, 0, 20, null, SortDirection.ASC))

        assertTrue(result.content.any { it.id == draft.id })
    }

    @Test
    fun `an ACTIVE salon is also listed, alongside DRAFT ones, with its real status`() {
        val active = activeSalon("Published Salon")

        val result = useCase.execute(ListPlatformSalonsQuery(admin.id, 0, 20, null, SortDirection.ASC))

        val found = result.content.find { it.id == active.id }
        assertEquals(SalonOnboardingStatus.ACTIVE, found?.onboardingStatus)
    }

    @Test
    fun `a MANAGER - a normal authenticated salon user - cannot access platform-wide salon listing`() {
        draftSalon("Someone Else's Draft")

        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(ListPlatformSalonsQuery(manager.id, 0, 20, null, SortDirection.ASC))
        }
    }

    @Test
    fun `a CUSTOMER cannot access platform-wide salon listing either`() {
        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(ListPlatformSalonsQuery(customer.id, 0, 20, null, SortDirection.ASC))
        }
    }

    @Test
    fun `pagination works across a mix of DRAFT and ACTIVE salons`() {
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")
        names.forEachIndexed { index, name ->
            if (index % 2 == 0) draftSalon(name) else activeSalon(name)
        }

        val firstPage = useCase.execute(ListPlatformSalonsQuery(admin.id, 0, 2, null, SortDirection.ASC))
        val secondPage = useCase.execute(ListPlatformSalonsQuery(admin.id, 1, 2, null, SortDirection.ASC))

        assertEquals(2, firstPage.content.size)
        assertEquals(2, secondPage.content.size)
        assertEquals(5L, firstPage.totalElements)
        assertEquals(listOf("Alpha", "Bravo"), firstPage.content.map { it.name })
        assertEquals(listOf("Charlie", "Delta"), secondPage.content.map { it.name })
    }

    @Test
    fun `the existing name filter continues to work, matching DRAFT and ACTIVE salons alike`() {
        draftSalon("Glow Studio Draft")
        activeSalon("Glow Studio Active")
        draftSalon("Unrelated Salon")

        val result = useCase.execute(ListPlatformSalonsQuery(admin.id, 0, 20, "glow", SortDirection.ASC))

        assertEquals(2, result.content.size)
        assertTrue(result.content.all { it.name.contains("Glow", ignoreCase = true) })
    }
}
