package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

/**
 * Platform Management API Contract (Salons) - Admin Salon Visibility: the real gap left open after
 * Web's Salon Onboarding/Activation lifecycle shipped - `GET /api/v1/salons` (`SalonController.list`)
 * is, and must remain, the customer/manager-facing "browse ACTIVE salons" contract
 * ([SalonRepository.findAllActive]'s own filter is never touched here or by this use case).
 * Mirrors [ListPlatformManagersUseCase]'s own shape exactly: read-only, so PLATFORM_ADMIN and
 * PLATFORM_REVIEWER have nothing to differ on (same reasoning that use case's own doc comment
 * gives), authorized entirely through [PlatformAuthorizationResolver] - never
 * [ai.rojan.backend.application.salon.SalonPermissionResolver] or any salon membership check, this
 * is platform-wide oversight, not salon-scoped access.
 */
data class ListPlatformSalonsQuery(
    val callerId: UserId,
    val page: Int,
    val size: Int,
    val name: String?,
    val sortDirection: SortDirection,
)

class ListPlatformSalonsUseCase(
    private val salonRepository: SalonRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListPlatformSalonsQuery): PageResult<Salon> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        return salonRepository.findAllForPlatform(
            PageRequest(query.page, query.size),
            query.name,
            query.sortDirection,
        )
    }
}
