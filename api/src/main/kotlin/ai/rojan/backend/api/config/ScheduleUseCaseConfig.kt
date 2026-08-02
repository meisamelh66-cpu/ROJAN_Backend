package ai.rojan.backend.api.config

import ai.rojan.backend.application.schedule.CreateSpecialistBlockUseCase
import ai.rojan.backend.application.schedule.CreateSpecialistLeaveUseCase
import ai.rojan.backend.application.schedule.RemoveScheduleOverrideUseCase
import ai.rojan.backend.application.schedule.RemoveSpecialistBlockUseCase
import ai.rojan.backend.application.schedule.RemoveSpecialistLeaveUseCase
import ai.rojan.backend.application.schedule.RemoveSpecialistWeeklyAvailabilityUseCase
import ai.rojan.backend.application.schedule.RemoveWorkingHoursUseCase
import ai.rojan.backend.application.schedule.SetScheduleOverrideUseCase
import ai.rojan.backend.application.schedule.SetSpecialistWeeklyAvailabilityUseCase
import ai.rojan.backend.application.schedule.SetWorkingHoursUseCase
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free schedule-management application use cases as Spring beans. */
@Configuration
class ScheduleUseCaseConfig {

    @Bean
    fun setWorkingHoursUseCase(salonRepository: SalonRepository, workingHoursRepository: WorkingHoursRepository) =
        SetWorkingHoursUseCase(salonRepository, workingHoursRepository)

    @Bean
    fun removeWorkingHoursUseCase(salonRepository: SalonRepository, workingHoursRepository: WorkingHoursRepository) =
        RemoveWorkingHoursUseCase(salonRepository, workingHoursRepository)

    @Bean
    fun setSpecialistWeeklyAvailabilityUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
    ) = SetSpecialistWeeklyAvailabilityUseCase(salonRepository, specialistRepository, weeklyAvailabilityRepository)

    @Bean
    fun removeSpecialistWeeklyAvailabilityUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
    ) = RemoveSpecialistWeeklyAvailabilityUseCase(salonRepository, specialistRepository, weeklyAvailabilityRepository)

    @Bean
    fun setScheduleOverrideUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        overrideRepository: SpecialistScheduleOverrideRepository,
    ) = SetScheduleOverrideUseCase(salonRepository, specialistRepository, overrideRepository)

    @Bean
    fun removeScheduleOverrideUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        overrideRepository: SpecialistScheduleOverrideRepository,
    ) = RemoveScheduleOverrideUseCase(salonRepository, specialistRepository, overrideRepository)

    @Bean
    fun createSpecialistLeaveUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        leaveRepository: SpecialistLeaveRepository,
    ) = CreateSpecialistLeaveUseCase(salonRepository, specialistRepository, leaveRepository)

    @Bean
    fun removeSpecialistLeaveUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        leaveRepository: SpecialistLeaveRepository,
    ) = RemoveSpecialistLeaveUseCase(salonRepository, specialistRepository, leaveRepository)

    @Bean
    fun createSpecialistBlockUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        blockRepository: SpecialistBlockRepository,
    ) = CreateSpecialistBlockUseCase(salonRepository, specialistRepository, blockRepository)

    @Bean
    fun removeSpecialistBlockUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        blockRepository: SpecialistBlockRepository,
    ) = RemoveSpecialistBlockUseCase(salonRepository, specialistRepository, blockRepository)
}
