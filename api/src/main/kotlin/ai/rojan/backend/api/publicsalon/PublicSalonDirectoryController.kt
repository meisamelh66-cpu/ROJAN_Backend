package ai.rojan.backend.api.publicsalon

import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Public Salon Marketplace (Phase 1): the aggregate, cross-salon counterpart to
 * [PublicSalonController]'s single-salon-by-slug lookup - lets a real, anonymous customer discover
 * salons without already knowing one's slug. A separate controller, not a method on
 * [PublicSalonController], because that one is entirely `{slug}`-scoped
 * (`@RequestMapping("/api/v1/public/salons/{slug}")`); `GET /api/v1/public/salons` (no further path
 * segment) is a distinct URL pattern Spring resolves independently, no collision.
 *
 * Deliberately never reuses `SalonController.list`'s `findAllActive` (authenticated, checks only
 * [Salon.active]) - real public discoverability also requires
 * [ai.rojan.backend.domain.salon.SalonOnboardingStatus.ACTIVE] (a still-[ai.rojan.backend.domain.salon.SalonOnboardingStatus.DRAFT]
 * salon must never leak here), enforced by the dedicated
 * [SalonRepository.findAllPubliclyDiscoverable]. Lives under the already-`permitAll`
 * `/api/v1/public` wildcard prefix (`SecurityConfig`) - no security-configuration change needed.
 */
@RestController
@RequestMapping("/api/v1/public/salons")
@Tag(name = "Public Salon Directory")
class PublicSalonDirectoryController(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
) {

    @GetMapping
    @Operation(summary = "Browse publicly discoverable salons, paginated and optionally filtered by city and/or name")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "ASC") sortDirection: String,
    ): PagedResponse<PublicSalonListResponse> {
        val result = salonRepository.findAllPubliclyDiscoverable(
            PageRequest(page, size),
            city,
            search,
            SortDirection.valueOf(sortDirection.uppercase()),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    private fun Salon.toResponse() = PublicSalonListResponse(
        id = id.value,
        slug = slug,
        name = name,
        logoUrl = logoMediaId?.let { resolveMediaUrl(it) },
        coverUrl = coverMediaId?.let { resolveMediaUrl(it) },
        city = city,
    )

    private fun Salon.resolveMediaUrl(mediaId: MediaAssetId): String? =
        mediaAssetRepository.findByIdAndSalonId(mediaId, id)?.let { mediaStoragePort.resolveUrl(it.storageKey) }
}
