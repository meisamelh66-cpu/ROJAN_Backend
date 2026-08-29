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
        ): Salon = Salon(
            id, ownerId, name, description, phone, email, address, slug,
            onboardingStatus, logoMediaId, coverMediaId, latitude, longitude, city, active, createdAt, updatedAt,
        )
    }
}

/** The two identity-media slots a [Salon] can have a current [ai.rojan.backend.domain.media.MediaAsset] assigned to. */
enum class IdentitySlot { LOGO, COVER }
