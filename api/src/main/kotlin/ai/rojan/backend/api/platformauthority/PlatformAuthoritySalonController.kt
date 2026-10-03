package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.api.salon.SalonCompletenessResponse
import ai.rojan.backend.application.platformauthority.GetPlatformSalonCompletenessQuery
import ai.rojan.backend.application.platformauthority.GetPlatformSalonCompletenessUseCase
import ai.rojan.backend.application.platformauthority.ListPlatformSalonsQuery
import ai.rojan.backend.application.platformauthority.ListPlatformSalonsUseCase
import ai.rojan.backend.application.platformauthority.ReinstatePlatformSalonCommand
import ai.rojan.backend.application.platformauthority.ReinstatePlatformSalonUseCase
import ai.rojan.backend.application.platformauthority.RemovePlatformSalonMediaCommand
import ai.rojan.backend.application.platformauthority.RemovePlatformSalonMediaUseCase
import ai.rojan.backend.application.platformauthority.SuspendPlatformSalonCommand
import ai.rojan.backend.application.platformauthority.SuspendPlatformSalonUseCase
import ai.rojan.backend.application.platformauthority.UpdatePlatformSalonCommand
import ai.rojan.backend.application.platformauthority.UpdatePlatformSalonUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.salon.PlatformSalonFilter
import ai.rojan.backend.domain.salon.PlatformSalonResult
import ai.rojan.backend.domain.salon.PlatformSalonSort
import ai.rojan.backend.domain.salon.PlatformSalonSortField
import ai.rojan.backend.domain.salon.PlatformSalonStatus
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform Management API Contract (Salons) - Admin Salon Visibility/Edit/Suspend/Media
 * (`docs/backend-requirements/platform-admin-scalability.md` §4). `GET /api/v1/salons`
 * (`SalonController.list`) is, and must remain, the customer/manager-facing "browse ACTIVE salons"
 * contract - its own `findAllActive` filter (`active AND onboardingStatus == ACTIVE`) is never
 * touched by this file. This is a separate, dedicated, platform-role-gated controller instead,
 * mirroring [PlatformAuthorityManagerController]'s own shape: [list] is open to PLATFORM_ADMIN or
 * PLATFORM_REVIEWER (read-only, nothing for the two roles to differ on); [suspend]/[reinstate]/
 * [update]/[removeMedia] are PLATFORM_ADMIN only (same "PLATFORM_REVIEWER never mutates" split
 * [PlatformAuthorityManagerController] already establishes for deactivate/reactivate).
 * Authorization is entirely [ai.rojan.backend.application.platformauthority.
 * PlatformAuthorizationResolver] inside each use case, never
 * [ai.rojan.backend.application.salon.SalonPermissionResolver] - this controller never inspects a
 * caller's salon membership, only their platform role.
 */
@RestController
@RequestMapping("/api/v1/platform-authority/salons")
@Tag(name = "Platform Authority - Salons")
class PlatformAuthoritySalonController(
    private val listPlatformSalonsUseCase: ListPlatformSalonsUseCase,
    private val suspendPlatformSalonUseCase: SuspendPlatformSalonUseCase,
    private val reinstatePlatformSalonUseCase: ReinstatePlatformSalonUseCase,
    private val updatePlatformSalonUseCase: UpdatePlatformSalonUseCase,
    private val removePlatformSalonMediaUseCase: RemovePlatformSalonMediaUseCase,
    private val getPlatformSalonCompletenessUseCase: GetPlatformSalonCompletenessUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
) {

    @GetMapping
    @Operation(
        summary = "List every salon regardless of onboarding/active status, paginated, filtered and sorted",
        description = "PLATFORM_ADMIN or PLATFORM_REVIEWER only. Unlike GET /api/v1/salons, a DRAFT or suspended (inactive) salon is included here with its real onboardingStatus/active fields. " +
            "Filters: name/owner (substring), phone (prefix), status=PUBLISHED|DRAFT|INACTIVE, verified, city (exact). Sort: `sort=createdAt|name,asc|desc`.",
    )
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) owner: String?,
        @RequestParam(required = false) phone: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) verified: Boolean?,
        @RequestParam(required = false) city: String?,
        @RequestParam(defaultValue = "createdAt,desc") sort: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<PlatformSalonResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val result = listPlatformSalonsUseCase.execute(
            ListPlatformSalonsQuery(
                callerId = callerId,
                page = page,
                size = size,
                filter = PlatformSalonFilter(
                    name = name,
                    owner = owner,
                    phone = phone,
                    status = status?.let { PlatformSalonStatus.valueOf(it.uppercase()) },
                    verified = verified,
                    city = city,
                ),
                sort = parseSort(sort),
            ),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    @GetMapping("/{salonId}/completeness")
    @Operation(
        summary = "Read a salon's business-profile completion fields (PLATFORM_ADMIN or PLATFORM_REVIEWER)",
        description = "The platform-authorized equivalent of GET /api/v1/salons/{salonId}/completeness, which requires salon membership (MANAGE_SALON) and 403s for a platform caller who isn't one. Same real fields, same missingForActivation list - intended to pre-fill the admin edit form before a PUT, never a separate source of truth.",
    )
    fun completeness(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): SalonCompletenessResponse {
        val callerId = currentUserResolver.resolve(principal)
        val result = getPlatformSalonCompletenessUseCase.execute(GetPlatformSalonCompletenessQuery(callerId, SalonId(salonId)))
        return SalonCompletenessResponse(
            salonId = salonId,
            activityStartJalaliYear = result.salon.activityStartJalaliYear,
            hasInternalExtensions = result.salon.hasInternalExtensions,
            sellsProducts = result.salon.sellsProducts,
            hasCafe = result.salon.hasCafe,
            hasStaffUniform = result.salon.hasStaffUniform,
            isNeighborhoodSalon = result.salon.isNeighborhoodSalon,
            isCityCenterSalon = result.salon.isCityCenterSalon,
            primaryContactMembershipId = result.salon.primaryContactMembershipId?.value,
            missingForActivation = result.missingForActivation,
        )
    }

    @PostMapping("/{salonId}/suspend")
    @Operation(
        summary = "Suspend a salon (soft-deactivate) with a required reason - removes it from every customer-facing surface (PLATFORM_ADMIN only)",
        description = "Reuses the exact same Salon.deactivate() transition an owner's own DELETE /api/v1/salons/{id} already causes - never a hard delete, never touches the owner's account, any membership, media, service, specialist, working-hours or booking data. Recorded as a SALON_SUSPENDED audit event.",
    )
    fun suspend(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: SuspendPlatformSalonRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformSalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = suspendPlatformSalonUseCase.execute(SuspendPlatformSalonCommand(callerId, SalonId(salonId), request.reason))
        return salon.toResponse(ownerName = null)
    }

    @PostMapping("/{salonId}/reinstate")
    @Operation(summary = "Reinstate a previously suspended salon (PLATFORM_ADMIN only)")
    fun reinstate(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): PlatformSalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = reinstatePlatformSalonUseCase.execute(ReinstatePlatformSalonCommand(callerId, SalonId(salonId)))
        return salon.toResponse(ownerName = null)
    }

    @PutMapping("/{salonId}")
    @Operation(summary = "Edit a salon's stored information - identity/contact, location and business-profile fields in one save (PLATFORM_ADMIN only)")
    fun update(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: UpdatePlatformSalonRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformSalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = updatePlatformSalonUseCase.execute(
            UpdatePlatformSalonCommand(
                callerId = callerId,
                salonId = SalonId(salonId),
                name = request.name,
                description = request.description,
                phone = request.phone,
                email = request.email,
                address = request.address,
                latitude = request.latitude,
                longitude = request.longitude,
                city = request.city,
                activityStartJalaliYear = request.activityStartJalaliYear,
                hasInternalExtensions = request.hasInternalExtensions,
                sellsProducts = request.sellsProducts,
                hasCafe = request.hasCafe,
                hasStaffUniform = request.hasStaffUniform,
                isNeighborhoodSalon = request.isNeighborhoodSalon,
                isCityCenterSalon = request.isCityCenterSalon,
            ),
        )
        return salon.toResponse(ownerName = null)
    }

    @DeleteMapping("/{salonId}/identity-media/{slot}")
    @Operation(
        summary = "Clear an invalid logo/cover - the salon's own media slot reference only (PLATFORM_ADMIN only)",
        description = "Does not delete the underlying media asset, and never uploads a replacement - the owner re-uploads through the normal flow. Recorded as a MEDIA_DELETED audit event when a media id was actually set.",
    )
    fun removeMedia(
        @PathVariable salonId: UUID,
        @PathVariable slot: IdentitySlot,
        @AuthenticationPrincipal principal: UserDetails,
    ): PlatformSalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = removePlatformSalonMediaUseCase.execute(RemovePlatformSalonMediaCommand(callerId, SalonId(salonId), slot))
        return salon.toResponse(ownerName = null)
    }

    /** `sort=field,direction` - same `PlatformSalonSortField`/`SortDirection` allowlist `PlatformSalonFilter`'s own doc comment references; an unrecognized field/direction falls back to the documented default (`createdAt,desc`), never a 400 for a stray value on a read endpoint. */
    private fun parseSort(raw: String): PlatformSalonSort {
        val parts = raw.split(",")
        val field = when (parts.getOrNull(0)?.trim()) {
            "name" -> PlatformSalonSortField.NAME
            else -> PlatformSalonSortField.CREATED_AT
        }
        val direction = when (parts.getOrNull(1)?.trim()?.uppercase()) {
            "ASC" -> SortDirection.ASC
            else -> SortDirection.DESC
        }
        return PlatformSalonSort(field, direction)
    }

    private fun PlatformSalonResult.toResponse(): PlatformSalonResponse = salon.toResponse(ownerName)

    /**
     * Same field mapping [ai.rojan.backend.api.salon.SalonController] uses for every other salon
     * response, plus [ownerName] - duplicated here rather than extracted/shared, to avoid touching
     * that already-shipped, independently-tested controller for this unrelated admin-only addition.
     */
    private fun Salon.toResponse(ownerName: String?) = PlatformSalonResponse(
        id = id.value,
        ownerId = ownerId.value,
        ownerName = ownerName,
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
