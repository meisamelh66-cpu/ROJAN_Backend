package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.PlatformSalonFilter
import ai.rojan.backend.domain.salon.PlatformSalonSort
import ai.rojan.backend.domain.salon.PlatformSalonSortField
import ai.rojan.backend.domain.salon.PlatformSalonStatus
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
        .also { salonRepository.ownerNames[it.id] = "Salon Manager" }
    private val customer = User.registerWithPhone(nextPhone(), "Just a Customer", UserRole.CUSTOMER)
        .also { userRepository.save(it) }

    private val noFilter = PlatformSalonFilter()
    private val byNameAsc = PlatformSalonSort(PlatformSalonSortField.NAME, SortDirection.ASC)

    private fun query(
        callerId: UserId,
        page: Int = 0,
        size: Int = 20,
        filter: PlatformSalonFilter = noFilter,
        sort: PlatformSalonSort = byNameAsc,
    ) = ListPlatformSalonsQuery(callerId, page, size, filter, sort)

    private fun draftSalon(name: String, ownerId: UserId = manager.id, phone: String = "+15550100"): Salon =
        Salon.create(
            ownerId = ownerId,
            name = name,
            description = null,
            phone = phone,
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.DRAFT,
        ).also { salonRepository.save(it) }

    private fun activeSalon(name: String, ownerId: UserId = manager.id, phone: String = "+15550100"): Salon =
        Salon.create(
            ownerId = ownerId,
            name = name,
            description = null,
            phone = phone,
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.ACTIVE,
        ).also { salonRepository.save(it) }

    @Test
    fun `a PLATFORM_ADMIN can list a DRAFT salon with its real onboardingStatus and active fields`() {
        val draft = draftSalon("Fresh Draft Salon")

        val result = useCase.execute(query(admin.id))

        val found = result.content.find { it.salon.id == draft.id }
        assertTrue(found != null, "the DRAFT salon must be visible to a platform admin")
        assertEquals(SalonOnboardingStatus.DRAFT, found!!.salon.onboardingStatus)
        assertTrue(found.salon.active, "a freshly created salon is still active=true, only onboardingStatus is DRAFT")
    }

    @Test
    fun `a PLATFORM_REVIEWER can also list a DRAFT salon - read access is shared with PLATFORM_ADMIN`() {
        val draft = draftSalon("Reviewer Visible Draft")

        val result = useCase.execute(query(reviewer.id))

        assertTrue(result.content.any { it.salon.id == draft.id })
    }

    @Test
    fun `an ACTIVE salon is also listed, alongside DRAFT ones, with its real status`() {
        val active = activeSalon("Published Salon")

        val result = useCase.execute(query(admin.id))

        val found = result.content.find { it.salon.id == active.id }
        assertEquals(SalonOnboardingStatus.ACTIVE, found?.salon?.onboardingStatus)
    }

    @Test
    fun `a MANAGER - a normal authenticated salon user - cannot access platform-wide salon listing`() {
        draftSalon("Someone Else's Draft")

        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(query(manager.id))
        }
    }

    @Test
    fun `a CUSTOMER cannot access platform-wide salon listing either`() {
        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(query(customer.id))
        }
    }

    @Test
    fun `pagination works across a mix of DRAFT and ACTIVE salons`() {
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")
        names.forEachIndexed { index, name ->
            if (index % 2 == 0) draftSalon(name) else activeSalon(name)
        }

        val firstPage = useCase.execute(query(admin.id, page = 0, size = 2))
        val secondPage = useCase.execute(query(admin.id, page = 1, size = 2))

        assertEquals(2, firstPage.content.size)
        assertEquals(2, secondPage.content.size)
        assertEquals(5L, firstPage.totalElements)
        assertEquals(listOf("Alpha", "Bravo"), firstPage.content.map { it.salon.name })
        assertEquals(listOf("Charlie", "Delta"), secondPage.content.map { it.salon.name })
    }

    @Test
    fun `the name filter continues to work, matching DRAFT and ACTIVE salons alike`() {
        draftSalon("Glow Studio Draft")
        activeSalon("Glow Studio Active")
        draftSalon("Unrelated Salon")

        val result = useCase.execute(query(admin.id, filter = PlatformSalonFilter(name = "glow")))

        assertEquals(2, result.content.size)
        assertTrue(result.content.all { it.salon.name.contains("Glow", ignoreCase = true) })
    }

    @Test
    fun `each row carries the real owner name, resolved server-side`() {
        activeSalon("Rose Salon")

        val result = useCase.execute(query(admin.id))

        assertEquals("Salon Manager", result.content.single().ownerName)
    }

    @Test
    fun `the owner filter matches by substring against the real owner name`() {
        val sara = User.registerWithPhone(nextPhone(), "Sara Ahmadi", UserRole.MANAGER)
            .also { userRepository.save(it) }
            .also { salonRepository.ownerNames[it.id] = "Sara Ahmadi" }
        activeSalon("Sara's Salon", ownerId = sara.id)
        activeSalon("Manager's Salon")

        val result = useCase.execute(query(admin.id, filter = PlatformSalonFilter(owner = "sara")))

        assertEquals(listOf("Sara's Salon"), result.content.map { it.salon.name })
    }

    @Test
    fun `the phone filter matches by prefix, never a substring elsewhere in the number`() {
        activeSalon("Prefix Match", phone = "+985550001")
        activeSalon("No Match", phone = "+985559990")

        val result = useCase.execute(query(admin.id, filter = PlatformSalonFilter(phone = "+98555000")))

        assertEquals(listOf("Prefix Match"), result.content.map { it.salon.name })
    }

    @Test
    fun `the status filter derives INACTIVE from active=false regardless of onboardingStatus`() {
        val suspended = activeSalon("Suspended Salon").also { it.deactivate(); salonRepository.save(it) }
        activeSalon("Still Published")

        val result = useCase.execute(query(admin.id, filter = PlatformSalonFilter(status = PlatformSalonStatus.INACTIVE)))

        assertEquals(listOf(suspended.id), result.content.map { it.salon.id })
    }

    @Test
    fun `the status filter distinguishes PUBLISHED from DRAFT among active salons`() {
        draftSalon("Draft One")
        activeSalon("Published One")

        val draftResult = useCase.execute(query(admin.id, filter = PlatformSalonFilter(status = PlatformSalonStatus.DRAFT)))
        val publishedResult = useCase.execute(query(admin.id, filter = PlatformSalonFilter(status = PlatformSalonStatus.PUBLISHED)))

        assertEquals(listOf("Draft One"), draftResult.content.map { it.salon.name })
        assertEquals(listOf("Published One"), publishedResult.content.map { it.salon.name })
    }

    @Test
    fun `sorting by createdAt desc returns the most recently created salon first`() {
        val first = activeSalon("First Created")
        val second = activeSalon("Second Created")

        val result = useCase.execute(
            query(admin.id, sort = PlatformSalonSort(PlatformSalonSortField.CREATED_AT, SortDirection.DESC)),
        )

        assertEquals(listOf(second.id, first.id), result.content.map { it.salon.id })
    }
}
