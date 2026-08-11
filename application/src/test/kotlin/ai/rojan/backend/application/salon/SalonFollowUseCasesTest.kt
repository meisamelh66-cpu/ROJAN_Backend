package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonFollowStatus
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

private fun newSalon(ownerId: UserId = UserId(UUID.randomUUID())) = Salon.create(
    ownerId = ownerId,
    name = "Test Salon",
    description = null,
    phone = "0912",
    email = null,
    address = "Somewhere",
)

class SalonFollowUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val followRepository = InMemorySalonFollowRepository()
    private val followSalonUseCase = FollowSalonUseCase(salonRepository, followRepository)
    private val unfollowSalonUseCase = UnfollowSalonUseCase(followRepository)
    private val listFollowedSalonsUseCase = ListFollowedSalonsUseCase(followRepository)

    private val customerId = UserId(UUID.randomUUID())
    private val salon = newSalon().also { salonRepository.save(it) }

    @Test
    fun `customer can follow a salon`() {
        val follow = followSalonUseCase.execute(FollowSalonCommand(customerId, salon.id))

        assertEquals(customerId, follow.customerId)
        assertEquals(salon.id, follow.salonId)
        assertEquals(SalonFollowStatus.ACTIVE, follow.status)
        assertTrue(follow.isActive)
    }

    @Test
    fun `following a nonexistent salon throws`() {
        assertThrows<SalonNotFoundException> {
            followSalonUseCase.execute(FollowSalonCommand(customerId, SalonId.new()))
        }
    }

    @Test
    fun `following an already-followed salon is idempotent, not a duplicate`() {
        val first = followSalonUseCase.execute(FollowSalonCommand(customerId, salon.id))
        val second = followSalonUseCase.execute(FollowSalonCommand(customerId, salon.id))

        assertEquals(first.id, second.id)
        val page = listFollowedSalonsUseCase.execute(ListFollowedSalonsQuery(customerId, PageRequest(0, 20)))
        assertEquals(1, page.totalElements)
    }

    @Test
    fun `customer can unfollow a salon`() {
        followSalonUseCase.execute(FollowSalonCommand(customerId, salon.id))

        unfollowSalonUseCase.execute(UnfollowSalonCommand(customerId, salon.id))

        val page = listFollowedSalonsUseCase.execute(ListFollowedSalonsQuery(customerId, PageRequest(0, 20)))
        assertEquals(0, page.totalElements)
    }

    @Test
    fun `unfollowing a salon that was never followed is a no-op, not an error`() {
        unfollowSalonUseCase.execute(UnfollowSalonCommand(customerId, salon.id))

        val page = listFollowedSalonsUseCase.execute(ListFollowedSalonsQuery(customerId, PageRequest(0, 20)))
        assertEquals(0, page.totalElements)
    }

    @Test
    fun `re-following after unfollowing reactivates the same row`() {
        val original = followSalonUseCase.execute(FollowSalonCommand(customerId, salon.id))
        unfollowSalonUseCase.execute(UnfollowSalonCommand(customerId, salon.id))

        val refollowed = followSalonUseCase.execute(FollowSalonCommand(customerId, salon.id))

        assertEquals(original.id, refollowed.id)
        assertEquals(original.createdAt, refollowed.createdAt)
        assertTrue(refollowed.isActive)
    }

    @Test
    fun `a customer only ever sees their own followed salons - tenant isolation`() {
        val otherCustomerId = UserId(UUID.randomUUID())
        val otherSalon = newSalon().also { salonRepository.save(it) }

        followSalonUseCase.execute(FollowSalonCommand(customerId, salon.id))
        followSalonUseCase.execute(FollowSalonCommand(otherCustomerId, otherSalon.id))

        val myFollows = listFollowedSalonsUseCase.execute(ListFollowedSalonsQuery(customerId, PageRequest(0, 20)))

        assertEquals(1, myFollows.totalElements)
        assertEquals(salon.id, myFollows.content.single().salonId)
    }
}
