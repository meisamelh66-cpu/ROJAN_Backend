package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.salon.FavoriteSalonCommand
import ai.rojan.backend.application.salon.FavoriteSalonUseCase
import ai.rojan.backend.application.salon.ListFavoriteSalonsQuery
import ai.rojan.backend.application.salon.ListFavoriteSalonsUseCase
import ai.rojan.backend.application.salon.UnfavoriteSalonCommand
import ai.rojan.backend.application.salon.UnfavoriteSalonUseCase
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.salon.SalonFavorite
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Customer self-service "save a salon for personal use" - deliberately
 * separate from [SalonFollowController] (updates/news intent), per the
 * product decision not to merge the two concepts. Same self-only identity
 * reasoning as [SalonFollowController]'s own doc comment.
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "Customer Salon Favorites")
class SalonFavoriteController(
    private val favoriteSalonUseCase: FavoriteSalonUseCase,
    private val unfavoriteSalonUseCase: UnfavoriteSalonUseCase,
    private val listFavoriteSalonsUseCase: ListFavoriteSalonsUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping("/salons/{salonId}/favorite")
    @Operation(summary = "Save a salon to favorites for personal use (idempotent)")
    fun favorite(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): SalonFavoriteResponse {
        val customerId = currentUserResolver.resolve(principal)
        val favorite = favoriteSalonUseCase.execute(FavoriteSalonCommand(customerId, SalonId(salonId)))
        return favorite.toResponse()
    }

    @DeleteMapping("/salons/{salonId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a salon from favorites (idempotent)")
    fun unfavorite(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails) {
        val customerId = currentUserResolver.resolve(principal)
        unfavoriteSalonUseCase.execute(UnfavoriteSalonCommand(customerId, SalonId(salonId)))
    }

    @GetMapping("/favorite-salons")
    @Operation(summary = "List the authenticated customer's favorite salons, paginated")
    fun favoriteSalons(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<SalonFavoriteResponse> {
        val customerId = currentUserResolver.resolve(principal)
        val result = listFavoriteSalonsUseCase.execute(ListFavoriteSalonsQuery(customerId, PageRequest(page, size)))
        return result.toPagedResponse { it.toResponse() }
    }

    private fun SalonFavorite.toResponse() = SalonFavoriteResponse(
        id = id.value,
        salonId = salonId.value,
        createdAt = createdAt,
    )
}
