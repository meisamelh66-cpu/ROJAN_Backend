package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.salon.FollowSalonCommand
import ai.rojan.backend.application.salon.FollowSalonUseCase
import ai.rojan.backend.application.salon.ListFollowedSalonsQuery
import ai.rojan.backend.application.salon.ListFollowedSalonsUseCase
import ai.rojan.backend.application.salon.UnfollowSalonCommand
import ai.rojan.backend.application.salon.UnfollowSalonUseCase
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.salon.SalonFollow
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
 * Customer self-service "follow a salon for updates/news" - deliberately
 * separate from [SalonFavoriteController] (personal-bookmark intent), per
 * the product decision not to merge the two concepts. The caller's identity
 * always comes from [CurrentUserResolver] (JWT), never from a request body
 * or path parameter - there is no target-customer parameter to spoof, so a
 * caller can only ever follow/unfollow/list their own relationships.
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "Customer Salon Follows")
class SalonFollowController(
    private val followSalonUseCase: FollowSalonUseCase,
    private val unfollowSalonUseCase: UnfollowSalonUseCase,
    private val listFollowedSalonsUseCase: ListFollowedSalonsUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping("/salons/{salonId}/follow")
    @Operation(summary = "Follow a salon for updates/news (idempotent)")
    fun follow(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): SalonFollowResponse {
        val customerId = currentUserResolver.resolve(principal)
        val follow = followSalonUseCase.execute(FollowSalonCommand(customerId, SalonId(salonId)))
        return follow.toResponse()
    }

    @DeleteMapping("/salons/{salonId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unfollow a salon (idempotent)")
    fun unfollow(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails) {
        val customerId = currentUserResolver.resolve(principal)
        unfollowSalonUseCase.execute(UnfollowSalonCommand(customerId, SalonId(salonId)))
    }

    @GetMapping("/followed-salons")
    @Operation(summary = "List the authenticated customer's actively followed salons, paginated")
    fun followedSalons(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<SalonFollowResponse> {
        val customerId = currentUserResolver.resolve(principal)
        val result = listFollowedSalonsUseCase.execute(ListFollowedSalonsQuery(customerId, PageRequest(page, size)))
        return result.toPagedResponse { it.toResponse() }
    }

    private fun SalonFollow.toResponse() = SalonFollowResponse(
        id = id.value,
        salonId = salonId.value,
        status = status,
        createdAt = createdAt,
    )
}
