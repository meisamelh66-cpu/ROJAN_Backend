package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonOwnershipOutcome
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SalonSlugGenerator
import ai.rojan.backend.domain.user.UserId

data class CreatePublicSalonCommand(
    val ownerId: UserId,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
)

/** [created] is `false` when [salon] is a pre-existing salon this owner already had - nothing new was persisted. */
data class PublicSalonCreationResult(val salon: Salon, val created: Boolean)

/**
 * One-salon-per-account rule, scoped to the real public salon-creation/onboarding flow only
 * (`POST /api/v1/salons/onboarding`, called by the Website's `CreateSalonWizard`/
 * `RegistrationWizard` via `createSalonAction` - the actual "an end user creates their salon"
 * surface). Deliberately a SEPARATE use case from [CreateSalonUseCase], which stays completely
 * unchanged: that one remains the generic, unrestricted salon-creation building block every
 * internal/admin/platform flow and the existing integration-test suite already relies on for
 * legitimate multi-salon scenarios (a manager who owns several locations, platform-admin tooling,
 * test fixtures, etc.) - this rule must never reach any of those.
 *
 * If [CreatePublicSalonCommand.ownerId] already owns at least one salon (any status - a suspended
 * or still-DRAFT salon still counts), no new salon is created; the caller's existing salon is
 * returned instead with [PublicSalonCreationResult.created] = `false`, so the Web layer can show it
 * with an "edit" action rather than a bare rejection. Concurrency-safe: two simultaneous onboarding
 * requests for the same owner can never both create a salon - see
 * [SalonRepository.createForOwnerIfAbsent]'s own doc comment for how. Never migrates, merges, or
 * otherwise touches any pre-existing salon data; an account that already owned more than one salon
 * before this rule existed is completely unaffected (this never creates a *second* salon for them
 * either, but it never removes or alters the ones they already have).
 *
 * Immediate public visibility rule: a brand-new salon created here is [SalonOnboardingStatus.ACTIVE]
 * from the moment it is created (combined with [Salon.create]'s own `active = true` default, this
 * already satisfies [SalonRepository.findAllActive]/[SalonRepository.findAllPubliclyDiscoverable]'s
 * real `active AND onboardingStatus == ACTIVE` predicate - no further query change was needed). No
 * platform-admin approval step exists or is introduced. This never touches
 * [Salon.deactivate]/[Salon.reactivate] (the real, unchanged mechanism behind platform-admin
 * suspend/reinstate - see [ai.rojan.backend.application.platformauthority.SuspendPlatformSalonUseCase]) -
 * an admin can still suspend a brand-new salon to hide it, and reinstate it to make it public again,
 * exactly as before. [CreateSalonUseCase] is intentionally left creating [SalonOnboardingStatus.DRAFT]
 * salons, as before - this rule is scoped to the real public registration flow only.
 */
class CreatePublicSalonUseCase(
    private val salonRepository: SalonRepository,
) {
    fun execute(command: CreatePublicSalonCommand): PublicSalonCreationResult {
        val slug = SalonSlugGenerator.generateUnique(command.name) { salonRepository.existsBySlug(it) }
        val candidate = Salon.create(
            ownerId = command.ownerId,
            name = command.name,
            description = command.description,
            phone = command.phone,
            email = command.email,
            address = command.address,
            slug = slug,
            onboardingStatus = SalonOnboardingStatus.ACTIVE,
        )
        return when (val outcome = salonRepository.createForOwnerIfAbsent(command.ownerId, candidate)) {
            is SalonOwnershipOutcome.Created -> PublicSalonCreationResult(outcome.salon, created = true)
            is SalonOwnershipOutcome.AlreadyExists -> PublicSalonCreationResult(outcome.salon, created = false)
        }
    }
}
