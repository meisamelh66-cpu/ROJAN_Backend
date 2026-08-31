package ai.rojan.backend.api.website

import ai.rojan.backend.application.website.GetPublicWebsiteUseCase
import ai.rojan.backend.application.website.PublicWebsiteResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ROJAN AI Website Builder foundation: the real tenant-website configuration lookup behind
 * `GET /api/v1/public/{tenantSlug}/website` (ROJAN_Web's `apps/website/lib/api/website.ts`,
 * `TenantLayout`'s only real caller). Previously a hardcoded stub returning the same generic
 * `{"name":"ROJAN AI","description":"AI Beauty Platform",...}` for every tenant regardless of slug
 * - confirmed the real cause of every tenant subdomain 404ing, since that shape carried neither
 * `theme` nor `seo`, and the Web app's own malformed-response guard correctly rejected it as
 * not-found. This is a thin mapper only - [GetPublicWebsiteUseCase] does the real resolution
 * (public-discoverability guard, media URL resolution, related content), matching the existing
 * controller/use-case split the rest of this API already uses.
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Public Website")
class PublicWebsiteController(
    private val getPublicWebsiteUseCase: GetPublicWebsiteUseCase,
) {

    @GetMapping("/{tenantSlug}/website")
    @Operation(summary = "Get a tenant's real public website configuration by subdomain slug - identity, theme, SEO, contact, and content summaries")
    fun getWebsite(@PathVariable tenantSlug: String): PublicWebsiteResponse = toResponse(getPublicWebsiteUseCase.execute(tenantSlug))

    private fun toResponse(result: PublicWebsiteResult): PublicWebsiteResponse {
        val salon = result.salon
        val description = salon.description?.takeIf { it.isNotBlank() }
            ?: "رزرو آنلاین نوبت در ${salon.name} با روژان AI"
        val ogImageUrl = result.coverUrl ?: result.logoUrl

        return PublicWebsiteResponse(
            subdomain = salon.slug,
            // No custom-domain concept exists anywhere in this domain yet - real null, not a
            // fabricated value.
            customDomain = null,
            name = salon.name,
            description = description,
            logoUrl = result.logoUrl,
            coverUrl = result.coverUrl,
            gallery = result.galleryUrls,
            theme = PublicWebsiteThemeResponse(
                logoUrl = result.logoUrl,
                primaryColor = ROJAN_DEFAULT_PRIMARY_COLOR,
                secondaryColor = ROJAN_DEFAULT_SECONDARY_COLOR,
                fontFamily = ROJAN_DEFAULT_FONT_FAMILY,
                glassTheme = true,
                darkModeEnabled = false,
                brandAssetUrls = listOfNotNull(result.coverUrl),
            ),
            seo = PublicWebsiteSeoResponse(
                metaTitle = salon.name,
                metaDescription = description,
                ogImageUrl = ogImageUrl,
            ),
            contact = PublicWebsiteContactResponse(
                phone = salon.phone,
                email = salon.email,
                address = salon.address,
            ),
            servicesSummary = result.services.map { PublicWebsiteContentItemResponse(it.id, it.name) },
            specialistsSummary = result.specialists.map { PublicWebsiteContentItemResponse(it.id, it.name) },
            enabledSections = DEFAULT_ENABLED_SECTIONS,
        )
    }

    private companion object {
        // ROJAN's own established brand palette/typeface (rojanai.ir's own `.rojan-theme`, ROJAN_Web
        // - deep royal violet + warm gold, Vazirmatn for Persian text) - the real, uniform default
        // every tenant site renders with today, until real per-salon theme customization exists.
        const val ROJAN_DEFAULT_PRIMARY_COLOR = "#7c3aa6"
        const val ROJAN_DEFAULT_SECONDARY_COLOR = "#d4af7a"
        const val ROJAN_DEFAULT_FONT_FAMILY = "Vazirmatn"

        val DEFAULT_ENABLED_SECTIONS = listOf("HERO", "GALLERY", "SERVICES", "SPECIALISTS", "CONTACT")
    }
}
