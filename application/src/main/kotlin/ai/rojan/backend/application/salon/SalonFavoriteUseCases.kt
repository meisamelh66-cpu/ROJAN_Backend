package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonFavorite
import ai.rojan.backend.domain.salon.SalonFavoriteRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class FavoriteSalonCommand(val customerId: UserId, val salonId: SalonId)

/** Idempotent: favoriting an already-favorited salon returns the existing row unchanged. */
class FavoriteSalonUseCase(
    private val salonRepository: SalonRepository,
    private val favoriteRepository: SalonFavoriteRepository,
) {
    fun execute(command: FavoriteSalonCommand): SalonFavorite {
        salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())

        favoriteRepository.findByCustomerIdAndSalonId(command.customerId, command.salonId)?.let { return it }
        return favoriteRepository.save(SalonFavorite.create(command.customerId, command.salonId))
    }
}

data class UnfavoriteSalonCommand(val customerId: UserId, val salonId: SalonId)

/** Idempotent: removing a favorite that doesn't exist is a no-op - DELETE semantics. */
class UnfavoriteSalonUseCase(
    private val favoriteRepository: SalonFavoriteRepository,
) {
    fun execute(command: UnfavoriteSalonCommand) {
        favoriteRepository.deleteByCustomerIdAndSalonId(command.customerId, command.salonId)
    }
}

data class ListFavoriteSalonsQuery(val customerId: UserId, val pageRequest: PageRequest)

/** Only ever reads [ListFavoriteSalonsQuery.customerId]'s own rows - same structural-isolation reasoning as [ListFollowedSalonsUseCase]. */
class ListFavoriteSalonsUseCase(
    private val favoriteRepository: SalonFavoriteRepository,
) {
    fun execute(query: ListFavoriteSalonsQuery): PageResult<SalonFavorite> =
        favoriteRepository.findByCustomerId(query.customerId, query.pageRequest)
}
