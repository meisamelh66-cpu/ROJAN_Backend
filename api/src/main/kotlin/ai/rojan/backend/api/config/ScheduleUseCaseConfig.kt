package ai.rojan.backend.api.config

import ai.rojan.backend.application.salon.SalonPermissionResolver
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
    fun setWorkingHoursUseCase(salonRepository: SalonRepository, workingHoursRepository: WorkingHoursRepository, salonPermissionResolver: SalonPermissionResolver) =
        SetWorkingHoursUseCase(salonRepository, workingHoursRepository, salonPermissionResolver)

    @Bean
    fun removeWorkingHoursUseCase(salonRepository: SalonRepository, workingHoursRepository: WorkingHoursRepository, salonPermissionResolver: SalonPermissionResolver) =
        RemoveWorkingHoursUseCase(salonRepository, workingHoursRepository, salonPermissionResolver)

    @Bean
    fun setSpecialistWeeklyAvailabilityUseCase(
        specialistRepository: SpecialistRepository,
        weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = SetSpecialistWeeklyAvailabilityUseCase(specialistRepository, weeklyAvailabilityRepository, salonPermissionResolver)

    @Bean
    fun removeSpecialistWeeklyAvailabilityUseCase(
        specialistRepository: SpecialistRepository,
        weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RemoveSpecialistWeeklyAvailabilityUseCase(specialistRepository, weeklyAvailabilityRepository, salonPermissionResolver)

    @Bean
    fun setScheduleOverrideUseCase(
        specialistRepository: SpecialistRepository,
        overrideRepository: SpecialistScheduleOverrideRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = SetScheduleOverrideUseCase(specialistRepository, overrideRepository, salonPermissionResolver)

    @Bean
    fun removeScheduleOverrideUseCase(
        specialistRepository: SpecialistRepository,
        overrideRepository: SpecialistScheduleOverrideRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RemoveScheduleOverrideUseCase(specialistRepository, overrideRepository, salonPermissionResolver)

    @Bean
    fun createSpecialistLeaveUseCase(
        specialistRepository: SpecialistRepository,
        leaveRepository: SpecialistLeaveRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = CreateSpecialistLeaveUseCase(specialistRepository, leaveRepository, salonPermissionResolver)

    @Bean
    fun removeSpecialistLeaveUseCase(
        specialistRepository: SpecialistRepository,
        leaveRepository: SpecialistLeaveRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RemoveSpecialistLeaveUseCase(specialistRepository, leaveRepository, salonPermissionResolver)

    @Bean
    fun createSpecialistBlockUseCase(
        specialistRepository: SpecialistRepository,
        blockRepository: SpecialistBlockRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = CreateSpecialistBlockUseCase(specialistRepository, blockRepository, salonPermissionResolver)

    @Bean
    fun removeSpecialistBlockUseCase(
        specialistRepository: SpecialistRepository,
        blockRepository: SpecialistBlockRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RemoveSpecialistBlockUseCase(specialistRepository, blockRepository, salonPermissionResolver)
}
