package ai.rojan.backend.application.schedule

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
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

class SetWorkingHoursUseCase(
    private val salonRepository: SalonRepository,
    private val workingHoursRepository: WorkingHoursRepository,
) {
    fun execute(command: SetWorkingHoursCommand): WorkingHours {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) throw SalonAccessDeniedException(salon.id.value.toString())

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
) {
    fun execute(command: RemoveWorkingHoursCommand) {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) throw SalonAccessDeniedException(salon.id.value.toString())
        workingHoursRepository.deleteBySalonIdAndDayOfWeek(salon.id, command.dayOfWeek)
    }
}
