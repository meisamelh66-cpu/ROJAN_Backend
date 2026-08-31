package ai.rojan.backend.application.website

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaAssetStatus
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import java.util.UUID

data class PublicWebsiteContentItem(val id: UUID, val name: String)

/**
 * Real result of resolving a tenant subdomain slug to its actual public website content - the
 * salon aggregate itself plus every cross-aggregate piece [ai.rojan.backend.api.website.PublicWebsiteController]
 * needs to shape a response, gathered once here so the controller stays a thin mapper (same split
 * [ai.rojan.backend.api.publicsalon.PublicSalonController] already uses, just consolidated into one
 * real use case for this endpoint - see this file's own class doc for why one was warranted here).
 */
data class PublicWebsiteResult(
    val salon: Salon,
    val logoUrl: String?,
    val coverUrl: String?,
    val galleryUrls: List<String>,
    val services: List<PublicWebsiteContentItem>,
    val specialists: List<PublicWebsiteContentItem>,
)

/**
 * ROJAN AI Website Builder foundation: the real, first non-placeholder implementation of
 * `GET /api/v1/public/{tenantSlug}/website`. Previously a hardcoded stub (`PublicWebsiteController`
 * returned the same generic `{"name":"ROJAN AI","description":"AI Beauty Platform",...}` for every
 * tenant, regardless of slug) - live-confirmed as the real cause of every tenant subdomain 404ing:
 * the website app's own malformed-response guard (`lib/api/website.ts`'s `!website?.theme ||
 * !website?.seo` check) correctly rejected that shape as "not found", since it carried neither.
 *
 * Deliberately its own use case (unlike sibling single-purpose endpoints in
 * [ai.rojan.backend.api.publicsalon.PublicSalonController], which read repositories directly in the
 * controller) - a real Website Builder foundation needs one real, testable, reusable place that
 * decides what a tenant's public website actually contains, that this pass's own controller and any
 * future Website Builder feature (custom pages, section toggles, etc.) can both build on, not
 * duplicate.
 *
 * No per-salon theme customization exists anywhere in this domain yet (confirmed - [Salon] has no
 * color/font/layout fields at all) - this deliberately does NOT invent one. The one real,
 * salon-specific piece of "branding" that already exists (logo/cover media) is resolved for real,
 * here; a uniform ROJAN brand default is applied for the rest by the controller, honestly
 * documented as a platform-wide default rather than fabricated per-tenant data - the real
 * customization point ([ai.rojan.backend.domain.salon.Salon] gaining real theme fields) is future
 * Website Builder work, not something to fake now.
 */
class GetPublicWebsiteUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistRepository: SpecialistRepository,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(tenantSlug: String): PublicWebsiteResult {
        // Same public-discoverability guard as PublicSalonController.findSalonOrThrow - a DRAFT or
        // deactivated salon must 404 identically to an unknown slug, never distinguishable as
        // "exists but not ready" to an anonymous caller.
        val salon = salonRepository.findBySlug(tenantSlug)
            ?.takeIf { it.active && it.onboardingStatus == SalonOnboardingStatus.ACTIVE }
            ?: throw SalonNotFoundException(tenantSlug)

        val logoUrl = salon.logoMediaId?.let { resolveMediaUrl(salon.id, it) }
        val coverUrl = salon.coverMediaId?.let { resolveMediaUrl(salon.id, it) }

        val galleryUrls = mediaAssetRepository.findBySalonId(salon.id)
            .filter { it.mediaType in PUBLIC_GALLERY_TYPES && it.status == MediaAssetStatus.ACTIVE }
            // Same targeted-portfolio exclusion as PublicSalonController.gallery - a PORTFOLIO row
            // targeted at a specific specialist belongs to that specialist's own portfolio feed,
            // not the salon's general gallery.
            .filter { it.mediaType != MediaType.PORTFOLIO || it.targetId == null }
            .map { mediaStoragePort.resolveUrl(it.storageKey) }

        val services = serviceRepository.findBySalonId(salon.id)
            .filter { it.active }
            .map { PublicWebsiteContentItem(it.id.value, it.name) }

        val specialists = specialistRepository.findBySalonId(salon.id)
            .filter { it.active }
            .map { PublicWebsiteContentItem(it.id.value, it.displayName) }

        return PublicWebsiteResult(salon, logoUrl, coverUrl, galleryUrls, services, specialists)
    }

    private fun resolveMediaUrl(salonId: SalonId, mediaId: MediaAssetId): String? =
        mediaAssetRepository.findByIdAndSalonId(mediaId, salonId)?.let { mediaStoragePort.resolveUrl(it.storageKey) }

    private companion object {
        val PUBLIC_GALLERY_TYPES = setOf(MediaType.GALLERY, MediaType.PORTFOLIO)
    }
}
