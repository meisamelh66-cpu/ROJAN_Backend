package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.application.platformauthority.ListPlatformSalonsQuery
import ai.rojan.backend.application.platformauthority.ListPlatformSalonsUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.Salon
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Platform Management API Contract (Salons) - Admin Salon Visibility: `GET /api/v1/salons`
 * (`SalonController.list`) is, and must remain, the customer/manager-facing "browse ACTIVE salons"
 * contract - its own `findAllActive` filter (`active AND onboardingStatus == ACTIVE`) is never
 * touched by this file. This is a separate, dedicated, platform-role-gated endpoint instead,
 * mirroring [PlatformAuthorityManagerController]'s own shape exactly: PLATFORM_ADMIN or
 * PLATFORM_REVIEWER may list every salon regardless of status - read-only, so there is nothing for
 * the two roles to differ on (same reasoning [ListPlatformSalonsUseCase]'s own doc comment gives).
 * Authorization is entirely [ai.rojan.backend.application.platformauthority.
 * PlatformAuthorizationResolver] inside that use case, never
 * [ai.rojan.backend.application.salon.SalonPermissionResolver] - this controller never inspects a
 * caller's salon membership, only their platform role.
 */
@RestController
@RequestMapping("/api/v1/platform-authority/salons")
@Tag(name = "Platform Authority - Salons")
class PlatformAuthoritySalonController(
    private val listPlatformSalonsUseCase: ListPlatformSalonsUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
) {

    @GetMapping
    @Operation(
        summary = "List every salon regardless of onboarding/active status, paginated and optionally filtered by name",
        description = "PLATFORM_ADMIN or PLATFORM_REVIEWER only. Unlike GET /api/v1/salons, a DRAFT or deactivated salon is included here with its real onboardingStatus/active fields.",
    )
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) name: String?,
        @RequestParam(defaultValue = "ASC") sortDirection: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<SalonResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val result = listPlatformSalonsUseCase.execute(
            ListPlatformSalonsQuery(callerId, page, size, name, SortDirection.valueOf(sortDirection.uppercase())),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    /**
     * Same mapping [ai.rojan.backend.api.salon.SalonController] uses for every other salon
     * response - duplicated here rather than extracted/shared, to avoid touching that already-
     * shipped, independently-tested controller for this unrelated admin-only addition.
     */
    private fun Salon.toResponse() = SalonResponse(
        id = id.value,
        ownerId = ownerId.value,
        name = name,
        description = description,
        phone = phone,
        email = email,
        address = address,
        slug = slug,
        onboardingStatus = onboardingStatus,
        logoMediaId = logoMediaId?.value,
        coverMediaId = coverMediaId?.value,
        logoUrl = logoMediaId?.let { resolveMediaUrl(it) },
        coverImageUrl = coverMediaId?.let { resolveMediaUrl(it) },
        latitude = latitude,
        longitude = longitude,
        city = city,
        active = active,
        rojanVerified = rojanVerified,
        rojanVerifiedAt = rojanVerifiedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun Salon.resolveMediaUrl(mediaId: MediaAssetId): String? =
        mediaAssetRepository.findByIdAndSalonId(mediaId, id)?.let { mediaStoragePort.resolveUrl(it.storageKey) }
}
