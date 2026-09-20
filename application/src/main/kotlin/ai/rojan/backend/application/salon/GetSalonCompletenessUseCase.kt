package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import ai.rojan.backend.domain.user.UserId

/**
 * [salon] is returned as-is (not a separate DTO) so every completion field's
 * real persisted value - including the null/false distinction on the
 * optional-but-answered fields - reaches the caller unmodified; this use
 * case never converts an unanswered `null` into `false`. [missingForActivation]
 * reuses [missingSalonActivationRequirements] exactly - the same list
 * [ActivateSalonUseCase] itself enforces, not a separately invented
 * completeness percentage/algorithm.
 */
data class SalonCompletenessResult(
    val salon: Salon,
    val missingForActivation: List<String>,
)

class GetSalonCompletenessUseCase(
    private val salonRepository: SalonRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistRepository: SpecialistRepository,
    private val workingHoursRepository: WorkingHoursRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(salonId: SalonId, callerId: UserId): SalonCompletenessResult {
        val salon = salonRepository.findById(salonId)
            ?: throw SalonNotFoundException(salonId.value.toString())
        salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_SALON)

        val missing = missingSalonActivationRequirements(salon, serviceRepository, specialistRepository, workingHoursRepository)
        return SalonCompletenessResult(salon, missing)
    }
}
