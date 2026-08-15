package ai.rojan.backend.api.config

import ai.rojan.backend.application.booking.CancelBookingUseCase
import ai.rojan.backend.application.booking.CompleteBookingUseCase
import ai.rojan.backend.application.booking.ConfirmBookingUseCase
import ai.rojan.backend.application.booking.CreateBookingUseCase
import ai.rojan.backend.application.booking.GetAvailableSlotsUseCase
import ai.rojan.backend.application.booking.RescheduleBookingUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.salon.SpecialistServiceRepository
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free booking-engine application use cases as Spring beans. */
@Configuration
class BookingUseCaseConfig {

    @Bean
    fun createBookingUseCase(
        salonRepository: SalonRepository,
        serviceRepository: ServiceRepository,
        specialistRepository: SpecialistRepository,
        bookingRepository: BookingRepository,
        specialistServiceRepository: SpecialistServiceRepository,
    ) = CreateBookingUseCase(salonRepository, serviceRepository, specialistRepository, bookingRepository, specialistServiceRepository)

    @Bean
    fun confirmBookingUseCase(bookingRepository: BookingRepository, salonPermissionResolver: SalonPermissionResolver) =
        ConfirmBookingUseCase(bookingRepository, salonPermissionResolver)

    @Bean
    fun cancelBookingUseCase(bookingRepository: BookingRepository, salonPermissionResolver: SalonPermissionResolver) =
        CancelBookingUseCase(bookingRepository, salonPermissionResolver)

    @Bean
    fun completeBookingUseCase(bookingRepository: BookingRepository, salonPermissionResolver: SalonPermissionResolver) =
        CompleteBookingUseCase(bookingRepository, salonPermissionResolver)

    @Bean
    fun rescheduleBookingUseCase(
        bookingRepository: BookingRepository,
        serviceRepository: ServiceRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RescheduleBookingUseCase(bookingRepository, serviceRepository, salonPermissionResolver)

    @Bean
    fun getAvailableSlotsUseCase(
        specialistRepository: SpecialistRepository,
        serviceRepository: ServiceRepository,
        workingHoursRepository: WorkingHoursRepository,
        weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
        overrideRepository: SpecialistScheduleOverrideRepository,
        leaveRepository: SpecialistLeaveRepository,
        blockRepository: SpecialistBlockRepository,
        bookingRepository: BookingRepository,
        specialistServiceRepository: SpecialistServiceRepository,
    ) = GetAvailableSlotsUseCase(
        specialistRepository,
        serviceRepository,
        workingHoursRepository,
        weeklyAvailabilityRepository,
        overrideRepository,
        leaveRepository,
        blockRepository,
        bookingRepository,
        specialistServiceRepository,
    )
}
