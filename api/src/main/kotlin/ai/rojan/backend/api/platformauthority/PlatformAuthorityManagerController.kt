package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.platformauthority.DeactivatePlatformManagerCommand
import ai.rojan.backend.application.platformauthority.DeactivatePlatformManagerUseCase
import ai.rojan.backend.application.platformauthority.ListPlatformManagersQuery
import ai.rojan.backend.application.platformauthority.ListPlatformManagersUseCase
import ai.rojan.backend.application.platformauthority.PlatformManagerAccount
import ai.rojan.backend.application.platformauthority.ReactivatePlatformManagerCommand
import ai.rojan.backend.application.platformauthority.ReactivatePlatformManagerUseCase
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform Management API Contract (Manager) - PLATFORM_ADMIN or PLATFORM_REVIEWER may list,
 * PLATFORM_ADMIN only may deactivate/reactivate (mirrors [PlatformAuthorityReviewerController]'s
 * own admin-vs-reviewer split conceptually, but list is open to both here - unlike Reviewer
 * Management, which is admin-only for every operation since it manages platform authority itself,
 * this is an operational oversight/moderation surface both roles legitimately use, the same
 * "PLATFORM_REVIEWER can read, only PLATFORM_ADMIN mutates" shape the Certificates verification
 * workspace already establishes). Authorization is entirely
 * [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver] inside each use
 * case, never [ai.rojan.backend.application.salon.SalonPermissionResolver] - this controller never
 * inspects a caller's salon membership.
 */
@RestController
@RequestMapping("/api/v1/platform-authority/managers")
@Tag(name = "Platform Authority - Managers")
class PlatformAuthorityManagerController(
    private val listPlatformManagersUseCase: ListPlatformManagersUseCase,
    private val deactivatePlatformManagerUseCase: DeactivatePlatformManagerUseCase,
    private val reactivatePlatformManagerUseCase: ReactivatePlatformManagerUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "List MANAGER accounts, paginated and optionally searched by name/phone, with real salon associations (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "ASC") sortDirection: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<PlatformManagerResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val result = listPlatformManagersUseCase.execute(
            ListPlatformManagersQuery(callerId, page, size, search, SortDirection.valueOf(sortDirection.uppercase())),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    @PostMapping("/{managerId}/deactivate")
    @Operation(summary = "Deactivate a MANAGER account - blocks login/refresh only, never touches any salon this manager owns (PLATFORM_ADMIN only)")
    fun deactivate(@PathVariable managerId: UUID, @AuthenticationPrincipal principal: UserDetails): PlatformManagerMutationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return deactivatePlatformManagerUseCase.execute(
            DeactivatePlatformManagerCommand(callerId, UserId(managerId)),
        ).toMutationResponse()
    }

    @PostMapping("/{managerId}/reactivate")
    @Operation(summary = "Reactivate a previously deactivated MANAGER account (PLATFORM_ADMIN only)")
    fun reactivate(@PathVariable managerId: UUID, @AuthenticationPrincipal principal: UserDetails): PlatformManagerMutationResponse {
        val callerId = currentUserResolver.resolve(principal)
        return reactivatePlatformManagerUseCase.execute(
            ReactivatePlatformManagerCommand(callerId, UserId(managerId)),
        ).toMutationResponse()
    }

    private fun PlatformManagerAccount.toResponse() = PlatformManagerResponse(
        id = user.id.value,
        phoneNumber = user.phoneNumber?.value,
        fullName = user.fullName,
        active = user.active,
        createdAt = user.createdAt,
        salonAssociations = salonAssociations.map {
            ManagerSalonAssociationResponse(it.salonId.value, it.salonName, it.accessType, it.role)
        },
    )

    private fun User.toMutationResponse() = PlatformManagerMutationResponse(
        id = id.value,
        phoneNumber = phoneNumber?.value,
        fullName = fullName,
        active = active,
        createdAt = createdAt,
    )
}
