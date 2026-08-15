package ai.rojan.backend.domain.salon

/**
 * A salon's onboarding lifecycle - deliberately separate from [Salon.active]
 * (which means "not soft-deleted", unrelated to onboarding progress). Gates
 * *public discoverability* only (`api.publicsalon.PublicSalonController`) -
 * a [DRAFT] salon remains fully manageable by its owner (catalog, staff,
 * hours, even direct bookings) while they finish setup; it just doesn't
 * appear on the QR/public browsing surface until [ACTIVE].
 */
enum class SalonOnboardingStatus {
    DRAFT,
    ACTIVE,
}
