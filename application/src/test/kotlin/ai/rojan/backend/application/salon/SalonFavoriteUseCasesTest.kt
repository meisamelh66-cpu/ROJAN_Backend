package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
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

class SalonFavoriteUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val favoriteRepository = InMemorySalonFavoriteRepository()
    private val favoriteSalonUseCase = FavoriteSalonUseCase(salonRepository, favoriteRepository)
    private val unfavoriteSalonUseCase = UnfavoriteSalonUseCase(favoriteRepository)
    private val listFavoriteSalonsUseCase = ListFavoriteSalonsUseCase(favoriteRepository)

    private val customerId = UserId(UUID.randomUUID())
    private val salon = newSalon().also { salonRepository.save(it) }

    @Test
    fun `customer can favorite a salon`() {
        val favorite = favoriteSalonUseCase.execute(FavoriteSalonCommand(customerId, salon.id))

        assertEquals(customerId, favorite.customerId)
        assertEquals(salon.id, favorite.salonId)
    }

    @Test
    fun `favoriting a nonexistent salon throws`() {
        assertThrows<SalonNotFoundException> {
            favoriteSalonUseCase.execute(FavoriteSalonCommand(customerId, SalonId.new()))
        }
    }

    @Test
    fun `favoriting an already-favorited salon is idempotent, not a duplicate`() {
        val first = favoriteSalonUseCase.execute(FavoriteSalonCommand(customerId, salon.id))
        val second = favoriteSalonUseCase.execute(FavoriteSalonCommand(customerId, salon.id))

        assertEquals(first.id, second.id)
        val page = listFavoriteSalonsUseCase.execute(ListFavoriteSalonsQuery(customerId, PageRequest(0, 20)))
        assertEquals(1, page.totalElements)
    }

    @Test
    fun `customer can remove a favorite`() {
        favoriteSalonUseCase.execute(FavoriteSalonCommand(customerId, salon.id))

        unfavoriteSalonUseCase.execute(UnfavoriteSalonCommand(customerId, salon.id))

        val page = listFavoriteSalonsUseCase.execute(ListFavoriteSalonsQuery(customerId, PageRequest(0, 20)))
        assertEquals(0, page.totalElements)
    }

    @Test
    fun `removing a favorite that was never added is a no-op, not an error`() {
        unfavoriteSalonUseCase.execute(UnfavoriteSalonCommand(customerId, salon.id))

        val page = listFavoriteSalonsUseCase.execute(ListFavoriteSalonsQuery(customerId, PageRequest(0, 20)))
        assertEquals(0, page.totalElements)
    }

    @Test
    fun `a customer only ever sees their own favorite salons - tenant isolation`() {
        val otherCustomerId = UserId(UUID.randomUUID())
        val otherSalon = newSalon().also { salonRepository.save(it) }

        favoriteSalonUseCase.execute(FavoriteSalonCommand(customerId, salon.id))
        favoriteSalonUseCase.execute(FavoriteSalonCommand(otherCustomerId, otherSalon.id))

        val myFavorites = listFavoriteSalonsUseCase.execute(ListFavoriteSalonsQuery(customerId, PageRequest(0, 20)))

        assertEquals(1, myFavorites.totalElements)
        assertEquals(salon.id, myFavorites.content.single().salonId)
    }
}
