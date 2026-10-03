package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Public Salon Onboarding - one-salon-per-account, scoped ONLY to this use case (the real
 * `POST /api/v1/salons/onboarding` flow). Proves (1) a first-time caller gets a real, newly
 * created salon, (2) a caller who already owns a salon gets that exact salon back instead of a
 * second one, (3) this is unaffected by the salon's active/onboarding status, (4) a different
 * owner is never blocked by another account's salon, and (5) [CreateSalonUseCase] - the generic,
 * unrestricted building block every internal/admin/platform flow and the integration-test suite
 * relies on - is never touched or called by this class.
 */
class CreatePublicSalonUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val useCase = CreatePublicSalonUseCase(salonRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private fun command(ownerId: UserId = owner, name: String = "Glow Salon") = CreatePublicSalonCommand(
        ownerId = ownerId,
        name = name,
        description = "Full service salon",
        phone = "+1 555 0100",
        email = "hello@glow.example",
        address = "1 Main St",
    )

    @Test
    fun `a first-time caller gets a real, newly created salon`() {
        val result = useCase.execute(command())

        assertTrue(result.created)
        assertEquals(owner, result.salon.ownerId)
        assertEquals("Glow Salon", result.salon.name)
        assertEquals(result.salon, salonRepository.findById(result.salon.id))
    }

    @Test
    fun `immediate public visibility - a freshly registered salon is active and ACTIVE (not DRAFT) with no admin approval or owner activation step`() {
        val result = useCase.execute(command())

        assertTrue(result.salon.active)
        assertEquals(SalonOnboardingStatus.ACTIVE, result.salon.onboardingStatus)
    }

    @Test
    fun `immediate public visibility - the freshly registered salon is found by the real public-discovery query right away`() {
        val result = useCase.execute(command())

        val discoverable = salonRepository.findAllPubliclyDiscoverable(PageRequest(0, 20), city = null, nameFilter = null, sortDirection = SortDirection.ASC)

        assertTrue(discoverable.content.any { it.id == result.salon.id }, "a brand-new salon must be publicly discoverable immediately, without any admin approval step")
    }

    @Test
    fun `a caller who already has a salon gets that exact salon back, not a second one`() {
        val first = useCase.execute(command())

        val second = useCase.execute(command(name = "Second Attempt Salon"))

        assertFalse(second.created)
        assertEquals(first.salon.id, second.salon.id)
        assertEquals("Glow Salon", second.salon.name, "the real existing salon is returned unchanged, never renamed")
        assertEquals(1, salonRepository.findByOwnerId(owner).size, "no second salon must ever be persisted")
    }

    @Test
    fun `blocks a second salon regardless of the first salon's active status`() {
        val first = useCase.execute(command())
        val deactivated = first.salon.also { it.deactivate() }
        salonRepository.save(deactivated)

        val second = useCase.execute(command(name = "Second Attempt Salon"))

        assertFalse(second.created)
        assertEquals(first.salon.id, second.salon.id)
        assertEquals(1, salonRepository.findByOwnerId(owner).size)
    }

    @Test
    fun `a different owner is completely unaffected and gets their own real salon created`() {
        useCase.execute(command(owner, "Owner's Salon"))

        val result = useCase.execute(command(stranger, "Stranger's Salon"))

        assertTrue(result.created)
        assertEquals(stranger, result.salon.ownerId)
        assertEquals(1, salonRepository.findByOwnerId(stranger).size)
        assertEquals(1, salonRepository.findByOwnerId(owner).size)
    }

    @Test
    fun `a historical account that already owns multiple salons from before this rule existed is simply handed back the earliest one, with both pre-existing salons left completely untouched`() {
        val older = useCase.execute(command(name = "Older Salon")).salon
        val newer = ai.rojan.backend.domain.salon.Salon.create(
            ownerId = owner,
            name = "Newer Historical Salon",
            description = null,
            phone = "+1 555 0101",
            email = null,
            address = "1b Main St",
        ).also { salonRepository.save(it) }

        val result = useCase.execute(command(name = "Yet Another Attempt"))

        assertFalse(result.created)
        assertEquals(older.id, result.salon.id, "the earliest existing salon is the one returned")
        assertEquals(
            setOf(older.id, newer.id),
            salonRepository.findByOwnerId(owner).map { it.id }.toSet(),
            "both pre-existing historical salons must still exist, unmodified",
        )
    }
}
