package ai.rojan.backend.api.config

import ai.rojan.backend.application.booking.CancelBookingUseCase
import ai.rojan.backend.application.booking.CompleteBookingUseCase
import ai.rojan.backend.application.booking.ConfirmBookingUseCase
import ai.rojan.backend.application.booking.CreateBookingUseCase
import ai.rojan.backend.application.booking.GetAvailableSlotsUseCase
import ai.rojan.backend.application.booking.RescheduleBookingUseCase
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
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
    ) = CreateBookingUseCase(salonRepository, serviceRepository, specialistRepository, bookingRepository)

    @Bean
    fun confirmBookingUseCase(bookingRepository: BookingRepository, salonRepository: SalonRepository) =
        ConfirmBookingUseCase(bookingRepository, salonRepository)

    @Bean
    fun cancelBookingUseCase(bookingRepository: BookingRepository, salonRepository: SalonRepository) =
        CancelBookingUseCase(bookingRepository, salonRepository)

    @Bean
    fun completeBookingUseCase(bookingRepository: BookingRepository, salonRepository: SalonRepository) =
        CompleteBookingUseCase(bookingRepository, salonRepository)

    @Bean
    fun rescheduleBookingUseCase(
        bookingRepository: BookingRepository,
        salonRepository: SalonRepository,
        serviceRepository: ServiceRepository,
    ) = RescheduleBookingUseCase(bookingRepository, salonRepository, serviceRepository)

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
    ) = GetAvailableSlotsUseCase(
        specialistRepository,
        serviceRepository,
        workingHoursRepository,
        weeklyAvailabilityRepository,
        overrideRepository,
        leaveRepository,
        blockRepository,
        bookingRepository,
    )
}
