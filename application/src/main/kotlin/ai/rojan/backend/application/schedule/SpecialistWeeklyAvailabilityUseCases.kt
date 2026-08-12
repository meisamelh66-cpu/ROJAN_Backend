package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserId
import java.time.DayOfWeek

data class SetWeeklyAvailabilityCommand(
    val specialistId: SpecialistId,
    val callerId: UserId,
    val dayOfWeek: DayOfWeek,
    val intervals: List<TimeInterval>,
)

/** Owner/manager ([Permission.MANAGE_SCHEDULE_ALL]) for any specialist, or the specialist themself ([Permission.MANAGE_SCHEDULE_OWN], own record only). */
class SetSpecialistWeeklyAvailabilityUseCase(
    private val specialistRepository: SpecialistRepository,
    private val weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: SetWeeklyAvailabilityCommand): SpecialistWeeklyAvailability {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)

        val existing = weeklyAvailabilityRepository.findBySpecialistIdAndDayOfWeek(specialist.id, command.dayOfWeek)
        val availability = if (existing != null) {
            existing.updateIntervals(command.intervals)
            existing
        } else {
            SpecialistWeeklyAvailability.create(specialist.id, command.dayOfWeek, command.intervals)
        }
        return weeklyAvailabilityRepository.save(availability)
    }
}

data class RemoveWeeklyAvailabilityCommand(
    val specialistId: SpecialistId,
    val callerId: UserId,
    val dayOfWeek: DayOfWeek,
)

class RemoveSpecialistWeeklyAvailabilityUseCase(
    private val specialistRepository: SpecialistRepository,
    private val weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveWeeklyAvailabilityCommand) {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)
        weeklyAvailabilityRepository.deleteBySpecialistIdAndDayOfWeek(specialist.id, command.dayOfWeek)
    }
}
