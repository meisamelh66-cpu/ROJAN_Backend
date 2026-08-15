package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import ai.rojan.backend.domain.user.UserId
import java.time.DayOfWeek

data class SetWorkingHoursCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val dayOfWeek: DayOfWeek,
    val intervals: List<TimeInterval>,
)

/** Salon-wide (not per-specialist) - no "own schedule" self-fallback applies here, unlike the per-specialist schedule use cases in this package. */
class SetWorkingHoursUseCase(
    private val salonRepository: SalonRepository,
    private val workingHoursRepository: WorkingHoursRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: SetWorkingHoursCommand): WorkingHours {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SCHEDULE_ALL)

        val existing = workingHoursRepository.findBySalonIdAndDayOfWeek(salon.id, command.dayOfWeek)
        val workingHours = if (existing != null) {
            existing.updateIntervals(command.intervals)
            existing
        } else {
            WorkingHours.create(salon.id, command.dayOfWeek, command.intervals)
        }
        return workingHoursRepository.save(workingHours)
    }
}

data class RemoveWorkingHoursCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val dayOfWeek: DayOfWeek,
)

class RemoveWorkingHoursUseCase(
    private val salonRepository: SalonRepository,
    private val workingHoursRepository: WorkingHoursRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveWorkingHoursCommand) {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SCHEDULE_ALL)
        workingHoursRepository.deleteBySalonIdAndDayOfWeek(salon.id, command.dayOfWeek)
    }
}
