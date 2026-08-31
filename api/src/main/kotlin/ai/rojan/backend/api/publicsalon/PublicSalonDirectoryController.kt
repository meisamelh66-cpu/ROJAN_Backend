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
 *
 * LBS Architecture (Phase 5): `list` additionally accepts optional `lat`/`lng`/`radiusKm` - when
 * both `lat` and `lng` are supplied it switches to [SalonRepository.findNearby] (real, computed
 * Haversine distance, radius-bounded, distance-sorted) instead of the name-sorted
 * [SalonRepository.findAllPubliclyDiscoverable] path; `city`/`search`/`sortDirection` are ignored in
 * that mode (a "nearby" query is inherently its own sort). Every existing caller that never sends
 * `lat`/`lng` is completely unaffected - same query, same response shape plus one new, always-`null`
 * field ([PublicSalonListResponse.distanceKm]).
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
    @Operation(
        summary = "Browse publicly discoverable salons, paginated and optionally filtered by city and/or name",
        description = "Supplying both lat and lng switches to a real, distance-sorted \"nearby\" query within radiusKm " +
            "(city/search/sortDirection are ignored in that mode) - see this controller's own doc comment.",
    )
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "ASC") sortDirection: String,
        @RequestParam(required = false) lat: Double?,
        @RequestParam(required = false) lng: Double?,
        @RequestParam(defaultValue = "20.0") radiusKm: Double,
    ): PagedResponse<PublicSalonListResponse> {
        if (lat != null && lng != null) {
            return listNearby(page, size, lat, lng, radiusKm)
        }

        val result = salonRepository.findAllPubliclyDiscoverable(
            PageRequest(page, size),
            city,
            search,
            SortDirection.valueOf(sortDirection.uppercase()),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    /**
     * LBS Architecture (Phase 5): real, computed distance - never fabricated. Validates `lat`/`lng`
     * against the exact same real-world ranges [ai.rojan.backend.domain.salon.Salon.updateProfile]
     * already enforces when an owner sets a salon's own coordinates (so "a coordinate this API
     * accepts" and "a coordinate a salon can be given" stay the same real range), and `radiusKm` to a
     * sane, positive, bounded window - both via `require`, mapped to `400 INVALID_ARGUMENT` by the
     * existing `GlobalExceptionHandler`, same as every other validated input in this codebase.
     */
    private fun listNearby(page: Int, size: Int, lat: Double, lng: Double, radiusKm: Double): PagedResponse<PublicSalonListResponse> {
        require(lat in -90.0..90.0) { "lat must be between -90 and 90" }
        require(lng in -180.0..180.0) { "lng must be between -180 and 180" }
        require(radiusKm in 0.1..500.0) { "radiusKm must be between 0.1 and 500" }

        val result = salonRepository.findNearby(lat, lng, radiusKm, PageRequest(page, size))
        return result.toPagedResponse { it.salon.toResponse(distanceKm = it.distanceKm) }
    }

    private fun Salon.toResponse(distanceKm: Double? = null) = PublicSalonListResponse(
        id = id.value,
        slug = slug,
        name = name,
        logoUrl = logoMediaId?.let { resolveMediaUrl(it) },
        coverUrl = coverMediaId?.let { resolveMediaUrl(it) },
        city = city,
        distanceKm = distanceKm,
    )

    private fun Salon.resolveMediaUrl(mediaId: MediaAssetId): String? =
        mediaAssetRepository.findByIdAndSalonId(mediaId, id)?.let { mediaStoragePort.resolveUrl(it.storageKey) }
}
