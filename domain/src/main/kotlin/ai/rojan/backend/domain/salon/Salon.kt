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
    logoUrl: String?,
    latitude: Double?,
    longitude: Double?,
    active: Boolean,
    logoMediaId: MediaAssetId?,
    coverMediaId: MediaAssetId?,
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

    var logoUrl: String? = logoUrl
        private set

    var latitude: Double? = latitude
        private set

    var longitude: Double? = longitude
        private set

    var active: Boolean = active
        private set

    /** References into the media subsystem (Salon Identity Foundation Phase B) - never the media's own URL/bytes, see [ai.rojan.backend.domain.media.MediaAsset]. Null means "not set," not "use [logoUrl]" - [logoUrl] stays purely legacy (see [updateProfile]) and is never written by [assignIdentityMedia]. */
    var logoMediaId: MediaAssetId? = logoMediaId
        private set

    var coverMediaId: MediaAssetId? = coverMediaId
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
     * Profile completion fields (logo, geo-location) - deliberately separate
     * from [update] (core business fields) so an owner filling in the QR/map
     * presentation details doesn't need to re-submit name/phone/address too.
     */
    fun updateProfile(logoUrl: String?, latitude: Double?, longitude: Double?) {
        require(latitude == null || latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude == null || longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        this.logoUrl = logoUrl?.trim()?.ifBlank { null }
        this.latitude = latitude
        this.longitude = longitude
        this.updatedAt = Instant.now()
    }

    /**
     * Sets which already-uploaded [ai.rojan.backend.domain.media.MediaAsset]
     * (by id) is this salon's current logo/cover - explicit `null` clears
     * that slot (unlike [updateProfile]'s "null means leave unchanged"),
     * since this method's whole purpose is "set the identity media,"
     * clearing included. Verifying the referenced id actually belongs to
     * this salon is cross-aggregate (needs
     * [ai.rojan.backend.domain.media.MediaAssetRepository]) and
     * deliberately lives in `AssignSalonIdentityMediaUseCase`, not here -
     * same split as [activate]'s readiness check.
     */
    fun assignIdentityMedia(logoMediaId: MediaAssetId?, coverMediaId: MediaAssetId?) {
        this.logoMediaId = logoMediaId
        this.coverMediaId = coverMediaId
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
            logoUrl: String? = null,
            latitude: Double? = null,
            longitude: Double? = null,
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
                logoUrl = logoUrl?.trim()?.ifBlank { null },
                latitude = latitude,
                longitude = longitude,
                active = true,
                logoMediaId = null,
                coverMediaId = null,
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
            logoUrl: String?,
            latitude: Double?,
            longitude: Double?,
            active: Boolean,
            logoMediaId: MediaAssetId?,
            coverMediaId: MediaAssetId?,
            createdAt: Instant,
            updatedAt: Instant,
        ): Salon = Salon(
            id, ownerId, name, description, phone, email, address, slug,
            onboardingStatus, logoUrl, latitude, longitude, active, logoMediaId, coverMediaId, createdAt, updatedAt,
        )
    }
}
