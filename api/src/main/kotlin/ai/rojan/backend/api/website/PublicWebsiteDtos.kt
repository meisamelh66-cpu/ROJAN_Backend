package ai.rojan.backend.api.website

import java.util.UUID

/**
 * ROJAN AI Website Builder foundation - the real tenant-website configuration contract
 * `GET /api/v1/public/{tenantSlug}/website` returns. `subdomain`/`customDomain`/`theme`/`seo` match
 * the website app's already-established `PublicWebsite` TypeScript contract exactly
 * (`apps/website/lib/types/website.ts`, ROJAN_Web) - that shape is load-bearing and must never
 * change here without a coordinated Web-side change. Every other field is additive: real data the
 * previous hardcoded stub never carried, safe for the current Web client to simply ignore until it
 * has a reason to read them (extra JSON fields are never breaking).
 *
 * [enabledSections] is the one deliberate forward-compatibility hook for the future Website
 * Builder: today it is always the same fixed, real list (every section this endpoint actually
 * backs with real content), not a per-tenant toggle - no section-visibility concept exists in the
 * domain yet, so none is faked. The field exists now so a future per-tenant customization feature
 * has a stable place to plug into without another contract change.
 */
data class PublicWebsiteResponse(
    val subdomain: String,
    val customDomain: String?,
    val name: String,
    val description: String,
    val logoUrl: String?,
    val coverUrl: String?,
    val gallery: List<String>,
    val theme: PublicWebsiteThemeResponse,
    val seo: PublicWebsiteSeoResponse,
    val contact: PublicWebsiteContactResponse,
    val servicesSummary: List<PublicWebsiteContentItemResponse>,
    val specialistsSummary: List<PublicWebsiteContentItemResponse>,
    val enabledSections: List<String>,
)

/**
 * [primaryColor]/[secondaryColor]/[fontFamily]/[glassTheme]/[darkModeEnabled] are a real, uniform
 * ROJAN brand default - [ai.rojan.backend.domain.salon.Salon] has no per-tenant theme fields
 * anywhere in the domain yet, so none is invented here. [logoUrl]/[brandAssetUrls] are the one
 * genuinely real, salon-specific part of this object today.
 */
data class PublicWebsiteThemeResponse(
    val logoUrl: String?,
    val primaryColor: String,
    val secondaryColor: String,
    val fontFamily: String,
    val glassTheme: Boolean,
    val darkModeEnabled: Boolean,
    val brandAssetUrls: List<String>,
)

data class PublicWebsiteSeoResponse(
    val metaTitle: String,
    val metaDescription: String,
    val ogImageUrl: String?,
)

data class PublicWebsiteContactResponse(
    val phone: String,
    val email: String?,
    val address: String,
)

data class PublicWebsiteContentItemResponse(
    val id: UUID,
    val name: String,
)
