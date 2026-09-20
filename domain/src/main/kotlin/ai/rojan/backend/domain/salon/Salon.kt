package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.common.SalonNotActiveException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonId(val value: UUID) {
    companion object {
        fun new(): SalonId = SalonId(UUID.randomUUID())
    }
}

/**
 * Aggregate root for a salon business. Construction is only possible through
 * [create] (new salons) or [reconstitute] (rehydration from storage) so
 * invariants can never be bypassed.
 */
class Salon private constructor(
    val id: SalonId,
    val ownerId: UserId,
    name: String,
    description: String?,
    phone: String,
    email: String?,
    address: String,
    slug: String,
    onboardingStatus: SalonOnboardingStatus,
    logoMediaId: MediaAssetId?,
    coverMediaId: MediaAssetId?,
    latitude: Double?,
    longitude: Double?,
    city: String?,
    active: Boolean,
    activityStartJalaliYear: Int?,
    hasInternalExtensions: Boolean,
    sellsProducts: Boolean?,
    hasCafe: Boolean?,
    hasStaffUniform: Boolean?,
    isNeighborhoodSalon: Boolean?,
    isCityCenterSalon: Boolean?,
    primaryContactMembershipId: SalonMembershipId?,
    rojanVerified: Boolean,
    rojanVerifiedAt: Instant?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var name: String = name
        private set

    var description: String? = description
        private set

    var phone: String = phone
        private set

    var email: String? = email
        private set

    var address: String = address
        private set

    var slug: String = slug
        private set

    var onboardingStatus: SalonOnboardingStatus = onboardingStatus
        private set

    var logoMediaId: MediaAssetId? = logoMediaId
        private set

    var coverMediaId: MediaAssetId? = coverMediaId
        private set

    var latitude: Double? = latitude
        private set

    var longitude: Double? = longitude
        private set

    /** Public Salon Marketplace (Phase 1): a plain, free-typed city name - no structured city/province taxonomy exists yet (confirmed absent anywhere in this codebase during discovery). Deliberately not validated against a fixed list here; the marketplace listing filters on whatever real values salons have actually set. */
    var city: String? = city
        private set

    var active: Boolean = active
        private set

    /** Salon Completeness (V25) - the Jalali year the salon started operating. Raw year only; years-of-activity is a display-layer computation, never stored (see the migration's own doc comment). */
    var activityStartJalaliYear: Int? = activityStartJalaliYear
        private set

    /** Salon Completeness (V25) - whether [ai.rojan.backend.domain.salon.SalonInternalExtension] rows for this salon are meaningful. Defaults `false`; toggling it off never deletes existing extension rows (see that class's own doc comment). */
    var hasInternalExtensions: Boolean = hasInternalExtensions
        private set

    /** Salon Completeness (V25) - `null` means "not yet answered", distinct from an honest `false`. */
    var sellsProducts: Boolean? = sellsProducts
        private set

    var hasCafe: Boolean? = hasCafe
        private set

    var hasStaffUniform: Boolean? = hasStaffUniform
        private set

    /** Owner-declared only - see [ai.rojan.backend.domain.verification.SalonGeoClassificationReview] for the independently reviewer-verified counterpart. Never itself flips based on a review. */
    var isNeighborhoodSalon: Boolean? = isNeighborhoodSalon
        private set

    var isCityCenterSalon: Boolean? = isCityCenterSalon
        private set

    /** Salon Completeness (V25) - which existing [SalonMembership] is this salon's designated manager/reception contact. Never a new phone field - the number itself is that membership's own [ai.rojan.backend.domain.user.User.phoneNumber]. */
    var primaryContactMembershipId: SalonMembershipId? = primaryContactMembershipId
        private set

    /**
     * ROJAN Verification (V30) - a system-controlled projection of "is this
     * salon's latest concluded verification case APPROVED", not a source of
     * truth in its own right (the real source of truth is the
     * [ai.rojan.backend.domain.verification.SalonVerification] history) and
     * never owner-editable - the only writer is [projectRojanVerification].
     * Deliberately never read by activation ([activate]) or by any
     * public-discovery query - see that method's own doc comment for why.
     */
    var rojanVerified: Boolean = rojanVerified
        private set

    var rojanVerifiedAt: Instant? = rojanVerifiedAt
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(
        name: String,
        description: String?,
        phone: String,
        email: String?,
        address: String,
    ) {
        require(name.isNotBlank()) { "Salon name must not be blank" }
        require(phone.isNotBlank()) { "Salon phone must not be blank" }
        require(address.isNotBlank()) { "Salon address must not be blank" }
        this.name = name.trim()
        this.description = description?.trim()?.ifBlank { null }
        this.phone = phone.trim()
        this.email = email?.trim()?.ifBlank { null }
        this.address = address.trim()
        this.updatedAt = Instant.now()
    }

    /** Uniqueness is enforced by [SalonRepository]/the database, not here — this only guards the value's own shape. */
    fun changeSlug(newSlug: String) {
        require(newSlug.isNotBlank()) { "Salon slug must not be blank" }
        this.slug = newSlug.trim()
        this.updatedAt = Instant.now()
    }

    /**
     * Profile completion fields (geo-location, city) - deliberately separate from
     * [update] (core business fields) so an owner filling in the map
     * presentation details doesn't need to re-submit name/phone/address too.
     * Logo/cover are handled by [assignIdentityMedia], not here - see that
     * method's own doc comment for why. [city] joins this group (Public Salon
     * Marketplace Phase 1) for the same reason lat/long do - a marketplace
     * presentation detail, not a core business field.
     */
    fun updateProfile(latitude: Double?, longitude: Double?, city: String?) {
        require(latitude == null || latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude == null || longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        this.latitude = latitude
        this.longitude = longitude
        this.city = city?.trim()?.ifBlank { null }
        this.updatedAt = Instant.now()
    }

    /**
     * Salon Completeness profile fields - deliberately its own group, same
     * reasoning as [updateProfile]: an owner answering "do you have a cafe?"
     * shouldn't need to resubmit name/phone/address too. [activityStartJalaliYear]
     * is validated only for being positive - this codebase stores the raw
     * Jalali year the owner typed, never a computed years-of-activity figure,
     * and has no calendar-conversion utility to validate it against a "real"
     * range with (see the migration's own doc comment). Completeness/activation
     * *readiness* (is this required, is this enough) is cross-aggregate and
     * deliberately does not live here - same split [activate] already uses.
     */
    fun updateCompletionProfile(
        activityStartJalaliYear: Int?,
        hasInternalExtensions: Boolean,
        sellsProducts: Boolean?,
        hasCafe: Boolean?,
        hasStaffUniform: Boolean?,
        isNeighborhoodSalon: Boolean?,
        isCityCenterSalon: Boolean?,
        primaryContactMembershipId: SalonMembershipId?,
    ) {
        require(activityStartJalaliYear == null || activityStartJalaliYear > 0) { "Activity start year must be a positive Jalali year" }
        this.activityStartJalaliYear = activityStartJalaliYear
        this.hasInternalExtensions = hasInternalExtensions
        this.sellsProducts = sellsProducts
        this.hasCafe = hasCafe
        this.hasStaffUniform = hasStaffUniform
        this.isNeighborhoodSalon = isNeighborhoodSalon
        this.isCityCenterSalon = isCityCenterSalon
        this.primaryContactMembershipId = primaryContactMembershipId
        this.updatedAt = Instant.now()
    }

    /**
     * ROJAN Verification - the single writer of [rojanVerified]/[rojanVerifiedAt].
     * A minimal state-recording guard only, the same "guards the transition
     * itself" split [activate]'s own doc comment already establishes - the
     * cross-aggregate decision of *when* a salon becomes ROJAN VERIFIED
     * (reviewing its latest concluded verification case) belongs to a future
     * application-layer use case, not here.
     */
    fun projectRojanVerification(verified: Boolean, verifiedAt: Instant?) {
        this.rojanVerified = verified
        this.rojanVerifiedAt = verifiedAt
        this.updatedAt = Instant.now()
    }

    /**
     * The only writer of [logoMediaId]/[coverMediaId] - a `MediaAsset` id,
     * never a URL (Salon must reference media through IDs). The servable
     * URL is resolved from this id only at the API response boundary,
     * through the storage port - nothing here ever stores one. `null`
     * clears the slot - unlike [updateProfile]'s "null means leave
     * unchanged," this method's whole purpose is "set the identity media,"
     * clearing included. Verifying the referenced id actually belongs to
     * this salon is cross-aggregate (needs
     * [ai.rojan.backend.domain.media.MediaAssetRepository]) and
     * deliberately lives in `AssignIdentityMediaUseCase`, not here - same
     * split as [activate]'s readiness check.
     */
    fun assignIdentityMedia(slot: IdentitySlot, mediaId: MediaAssetId?) {
        when (slot) {
            IdentitySlot.LOGO -> this.logoMediaId = mediaId
            IdentitySlot.COVER -> this.coverMediaId = mediaId
        }
        this.updatedAt = Instant.now()
    }

    /**
     * [DRAFT] -> [SalonOnboardingStatus.ACTIVE]. The *readiness* check (does
     * this salon actually have services/staff/hours configured) is
     * cross-aggregate and deliberately lives in `ActivateSalonUseCase`, not
     * here - this method only guards the transition itself.
     */
    fun activate() {
        if (onboardingStatus == SalonOnboardingStatus.ACTIVE) return
        onboardingStatus = SalonOnboardingStatus.ACTIVE
        updatedAt = Instant.now()
    }

    /**
     * The single, reusable enforcement point for "this salon must be ACTIVE
     * to transact" - called by every use case that creates a real business
     * record against a salon (booking creation, CRM customer association),
     * not just the ones that read/present it. Deliberately separate from
     * [SalonOnboardingStatus]'s public-discoverability gate
     * ([ai.rojan.backend.domain.common.SalonNotFoundException] in
     * `PublicSalonController`) - a DRAFT salon is invisible to browsing *and*
     * now rejects transactions outright, closing the gap where a caller who
     * already had a DRAFT salon's id (never obtainable via the public/QR
     * surface, but not structurally impossible either) could still book
     * against it. Living on the aggregate itself - not a separate
     * application-layer policy class - keeps this a single source of truth
     * that every call site shares by construction; there is no dependency to
     * wire, and it cannot be bypassed by a use case that forgets to call it
     * out to some other service.
     */
    fun requireActivated() {
        if (onboardingStatus != SalonOnboardingStatus.ACTIVE) {
            throw SalonNotActiveException(id.value.toString())
        }
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        /**
         * [slug] defaults to a random, always-valid fallback (mirroring the
         * `V9__salon_slug.sql` backfill's own `'salon-' || <id prefix>`
         * shape), and [onboardingStatus] defaults to
         * [SalonOnboardingStatus.ACTIVE] - both so every one of this
         * codebase's many existing `Salon.create(owner, name, ...)` call
         * sites, production and test alike, keeps compiling *and behaving*
         * unchanged. Only the real onboarding path (`CreateSalonUseCase`)
         * passes a real slug and `onboardingStatus = DRAFT` explicitly.
         */
        fun create(
            ownerId: UserId,
            name: String,
            description: String?,
            phone: String,
            email: String?,
            address: String,
            slug: String? = null,
            onboardingStatus: SalonOnboardingStatus = SalonOnboardingStatus.ACTIVE,
            logoMediaId: MediaAssetId? = null,
            coverMediaId: MediaAssetId? = null,
            latitude: Double? = null,
            longitude: Double? = null,
            city: String? = null,
        ): Salon {
            require(name.isNotBlank()) { "Salon name must not be blank" }
            require(phone.isNotBlank()) { "Salon phone must not be blank" }
            require(address.isNotBlank()) { "Salon address must not be blank" }
            require(latitude == null || latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
            require(longitude == null || longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
            val now = Instant.now()
            val id = SalonId.new()
            val resolvedSlug = slug?.trim()?.ifBlank { null } ?: "salon-${id.value.toString().replace("-", "").take(10)}"
            return Salon(
                id = id,
                ownerId = ownerId,
                name = name.trim(),
                description = description?.trim()?.ifBlank { null },
                phone = phone.trim(),
                email = email?.trim()?.ifBlank { null },
                address = address.trim(),
                slug = resolvedSlug,
                onboardingStatus = onboardingStatus,
                logoMediaId = logoMediaId,
                coverMediaId = coverMediaId,
                latitude = latitude,
                longitude = longitude,
                city = city?.trim()?.ifBlank { null },
                active = true,
                // Salon Completeness / ROJAN Verification: every field below is unanswered/false/null
                // for a brand-new salon - matches V25/V30's own DEFAULT FALSE / nullable shape exactly,
                // and keeps every existing Salon.create(...) call site (production and test) compiling
                // and behaving unchanged, same precedent as onboardingStatus's own default above.
                activityStartJalaliYear = null,
                hasInternalExtensions = false,
                sellsProducts = null,
                hasCafe = null,
                hasStaffUniform = null,
                isNeighborhoodSalon = null,
                isCityCenterSalon = null,
                primaryContactMembershipId = null,
                rojanVerified = false,
                rojanVerifiedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: SalonId,
            ownerId: UserId,
            name: String,
            description: String?,
            phone: String,
            email: String?,
            address: String,
            slug: String,
            onboardingStatus: SalonOnboardingStatus,
            logoMediaId: MediaAssetId?,
            coverMediaId: MediaAssetId?,
            latitude: Double?,
            longitude: Double?,
            city: String?,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
            activityStartJalaliYear: Int? = null,
            hasInternalExtensions: Boolean = false,
            sellsProducts: Boolean? = null,
            hasCafe: Boolean? = null,
            hasStaffUniform: Boolean? = null,
            isNeighborhoodSalon: Boolean? = null,
            isCityCenterSalon: Boolean? = null,
            primaryContactMembershipId: SalonMembershipId? = null,
            rojanVerified: Boolean = false,
            rojanVerifiedAt: Instant? = null,
        ): Salon = Salon(
            id, ownerId, name, description, phone, email, address, slug,
            onboardingStatus, logoMediaId, coverMediaId, latitude, longitude, city, active,
            activityStartJalaliYear, hasInternalExtensions, sellsProducts, hasCafe, hasStaffUniform,
            isNeighborhoodSalon, isCityCenterSalon, primaryContactMembershipId, rojanVerified, rojanVerifiedAt,
            createdAt, updatedAt,
        )
    }
}

/** The two identity-media slots a [Salon] can have a current [ai.rojan.backend.domain.media.MediaAsset] assigned to. */
enum class IdentitySlot { LOGO, COVER }
