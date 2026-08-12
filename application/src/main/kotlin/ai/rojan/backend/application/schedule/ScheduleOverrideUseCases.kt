package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.ScheduleOverrideNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.ScheduleOverrideId
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverride
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDate

data class SetScheduleOverrideCommand(
    val specialistId: SpecialistId,
    val callerId: UserId,
    val date: LocalDate,
    val intervals: List<TimeInterval>,
    val reason: String?,
)

class SetScheduleOverrideUseCase(
    private val specialistRepository: SpecialistRepository,
    private val overrideRepository: SpecialistScheduleOverrideRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: SetScheduleOverrideCommand): SpecialistScheduleOverride {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)

        val existing = overrideRepository.findBySpecialistIdAndDate(specialist.id, command.date)
        val override = if (existing != null) {
            existing.update(command.intervals, command.reason)
            existing
        } else {
            SpecialistScheduleOverride.create(specialist.id, command.date, command.intervals, command.reason)
        }
        return overrideRepository.save(override)
    }
}

data class RemoveScheduleOverrideCommand(
    val overrideId: ScheduleOverrideId,
    val callerId: UserId,
)

class RemoveScheduleOverrideUseCase(
    private val specialistRepository: SpecialistRepository,
    private val overrideRepository: SpecialistScheduleOverrideRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveScheduleOverrideCommand) {
        val override = overrideRepository.findById(command.overrideId)
            ?: throw ScheduleOverrideNotFoundException(command.overrideId.value.toString())
        val specialist = specialistRepository.findById(override.specialistId)
            ?: throw SpecialistNotFoundException(override.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)
        overrideRepository.deleteById(override.id)
    }
}
