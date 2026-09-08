package ai.rojan.backend.api.config

import ai.rojan.backend.application.booking.CreateBookingUseCase
import ai.rojan.backend.application.customer.AddCustomerNoteUseCase
import ai.rojan.backend.application.customer.AddCustomerTagUseCase
import ai.rojan.backend.application.customer.CalculateCustomerLifetimeValueUseCase
import ai.rojan.backend.application.customer.CreateBookingForCustomerUseCase
import ai.rojan.backend.application.customer.CreateCustomerUseCase
import ai.rojan.backend.application.customer.GetCustomerBookingsUseCase
import ai.rojan.backend.application.customer.GetCustomerTimelineUseCase
import ai.rojan.backend.application.customer.RemoveCustomerTagUseCase
import ai.rojan.backend.application.customer.ResolveOrCreateSalonCustomerUseCase
import ai.rojan.backend.application.customer.UpdateCustomerUseCase
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerNoteRepository
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerTagRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free Customer CRM application use cases as Spring beans, mirroring [SalonUseCaseConfig]/[BookingUseCaseConfig]. */
@Configuration
class CustomerUseCaseConfig {

    @Bean
    fun createCustomerUseCase(salonRepository: SalonRepository, customerRepository: CustomerRepository) =
        CreateCustomerUseCase(salonRepository, customerRepository)

    @Bean
    fun resolveOrCreateSalonCustomerUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        userRepository: UserRepository,
    ) = ResolveOrCreateSalonCustomerUseCase(salonRepository, customerRepository, userRepository)

    @Bean
    fun updateCustomerUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerActivityRepository: CustomerActivityRepository,
    ) = UpdateCustomerUseCase(salonRepository, customerRepository, customerActivityRepository)

    @Bean
    fun addCustomerNoteUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerNoteRepository: CustomerNoteRepository,
    ) = AddCustomerNoteUseCase(salonRepository, customerRepository, customerNoteRepository)

    @Bean
    fun addCustomerTagUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerTagRepository: CustomerTagRepository,
        customerActivityRepository: CustomerActivityRepository,
    ) = AddCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)

    @Bean
    fun removeCustomerTagUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerTagRepository: CustomerTagRepository,
        customerActivityRepository: CustomerActivityRepository,
    ) = RemoveCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)

    @Bean
    fun getCustomerTimelineUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerActivityRepository: CustomerActivityRepository,
        customerNoteRepository: CustomerNoteRepository,
        bookingRepository: BookingRepository,
    ) = GetCustomerTimelineUseCase(salonRepository, customerRepository, customerActivityRepository, customerNoteRepository, bookingRepository)

    @Bean
    fun getCustomerBookingsUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        bookingRepository: BookingRepository,
    ) = GetCustomerBookingsUseCase(salonRepository, customerRepository, bookingRepository)

    @Bean
    fun calculateCustomerLifetimeValueUseCase(bookingRepository: BookingRepository, serviceRepository: ServiceRepository) =
        CalculateCustomerLifetimeValueUseCase(bookingRepository, serviceRepository)

    @Bean
    fun createBookingForCustomerUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        createBookingUseCase: CreateBookingUseCase,
    ) = CreateBookingForCustomerUseCase(salonRepository, customerRepository, createBookingUseCase)
}
