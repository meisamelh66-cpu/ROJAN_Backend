package ai.rojan.backend.application.schedule

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.SalonRepository
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

class SetSpecialistWeeklyAvailabilityUseCase(
    private val salonRepository: SalonRepository,
    private val specialistRepository: SpecialistRepository,
    private val weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
) {
    fun execute(command: SetWeeklyAvailabilityCommand): SpecialistWeeklyAvailability {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        val salon = salonRepository.findById(specialist.salonId)
            ?: throw SalonNotFoundException(specialist.salonId.value.toString())
        if (salon.ownerId != command.callerId) throw SalonAccessDeniedException(salon.id.value.toString())

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
    private val salonRepository: SalonRepository,
    private val specialistRepository: SpecialistRepository,
    private val weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
) {
    fun execute(command: RemoveWeeklyAvailabilityCommand) {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        val salon = salonRepository.findById(specialist.salonId)
            ?: throw SalonNotFoundException(specialist.salonId.value.toString())
        if (salon.ownerId != command.callerId) throw SalonAccessDeniedException(salon.id.value.toString())
        weeklyAvailabilityRepository.deleteBySpecialistIdAndDayOfWeek(specialist.id, command.dayOfWeek)
    }
}
