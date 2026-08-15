package ai.rojan.backend.api.config

import ai.rojan.backend.application.salon.FavoriteSalonUseCase
import ai.rojan.backend.application.salon.FollowSalonUseCase
import ai.rojan.backend.application.salon.ListFavoriteSalonsUseCase
import ai.rojan.backend.application.salon.ListFollowedSalonsUseCase
import ai.rojan.backend.application.salon.UnfavoriteSalonUseCase
import ai.rojan.backend.application.salon.UnfollowSalonUseCase
import ai.rojan.backend.domain.salon.SalonFavoriteRepository
import ai.rojan.backend.domain.salon.SalonFollowRepository
import ai.rojan.backend.domain.salon.SalonRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the customer-relationship (Follow/Favorite) application use cases as
 * Spring beans, mirroring [SalonUseCaseConfig] for the owner-facing salon
 * vertical. Kept as its own config file rather than folded into
 * [SalonUseCaseConfig] - this is a customer self-service bounded concern,
 * not salon management.
 */
@Configuration
class CustomerRelationshipUseCaseConfig {

    @Bean
    fun followSalonUseCase(salonRepository: SalonRepository, followRepository: SalonFollowRepository) =
        FollowSalonUseCase(salonRepository, followRepository)

    @Bean
    fun unfollowSalonUseCase(followRepository: SalonFollowRepository) =
        UnfollowSalonUseCase(followRepository)

    @Bean
    fun listFollowedSalonsUseCase(followRepository: SalonFollowRepository) =
        ListFollowedSalonsUseCase(followRepository)

    @Bean
    fun favoriteSalonUseCase(salonRepository: SalonRepository, favoriteRepository: SalonFavoriteRepository) =
        FavoriteSalonUseCase(salonRepository, favoriteRepository)

    @Bean
    fun unfavoriteSalonUseCase(favoriteRepository: SalonFavoriteRepository) =
        UnfavoriteSalonUseCase(favoriteRepository)

    @Bean
    fun listFavoriteSalonsUseCase(favoriteRepository: SalonFavoriteRepository) =
        ListFavoriteSalonsUseCase(favoriteRepository)
}
