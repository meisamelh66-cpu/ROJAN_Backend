package ai.rojan.backend.api.config

import ai.rojan.backend.application.booking.CreateBookingUseCase
import ai.rojan.backend.application.customer.AddCustomerNoteUseCase
import ai.rojan.backend.application.customer.AddCustomerTagUseCase
import ai.rojan.backend.application.customer.CalculateCustomerLifetimeValueUseCase
import ai.rojan.backend.application.customer.CreateBookingForCustomerUseCase
import ai.rojan.backend.application.customer.CreateCustomerIdentityUseCase
import ai.rojan.backend.application.customer.CreateCustomerUseCase
import ai.rojan.backend.application.customer.EnsureCustomerAssociationUseCase
import ai.rojan.backend.application.customer.GetCustomerBookingsUseCase
import ai.rojan.backend.application.customer.GetCustomerTimelineUseCase
import ai.rojan.backend.application.customer.RemoveCustomerTagUseCase
import ai.rojan.backend.application.customer.UpdateCustomerUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
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
    fun createCustomerUseCase(salonRepository: SalonRepository, customerRepository: CustomerRepository, salonPermissionResolver: SalonPermissionResolver) =
        CreateCustomerUseCase(salonRepository, customerRepository, salonPermissionResolver)

    @Bean
    fun createCustomerIdentityUseCase(salonRepository: SalonRepository, customerRepository: CustomerRepository, salonPermissionResolver: SalonPermissionResolver) =
        CreateCustomerIdentityUseCase(salonRepository, customerRepository, salonPermissionResolver)

    @Bean
    fun updateCustomerUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerActivityRepository: CustomerActivityRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = UpdateCustomerUseCase(salonRepository, customerRepository, customerActivityRepository, salonPermissionResolver)

    @Bean
    fun addCustomerNoteUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerNoteRepository: CustomerNoteRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = AddCustomerNoteUseCase(salonRepository, customerRepository, customerNoteRepository, salonPermissionResolver)

    @Bean
    fun addCustomerTagUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerTagRepository: CustomerTagRepository,
        customerActivityRepository: CustomerActivityRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = AddCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository, salonPermissionResolver)

    @Bean
    fun removeCustomerTagUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerTagRepository: CustomerTagRepository,
        customerActivityRepository: CustomerActivityRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RemoveCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository, salonPermissionResolver)

    @Bean
    fun getCustomerTimelineUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        customerActivityRepository: CustomerActivityRepository,
        customerNoteRepository: CustomerNoteRepository,
        bookingRepository: BookingRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = GetCustomerTimelineUseCase(salonRepository, customerRepository, customerActivityRepository, customerNoteRepository, bookingRepository, salonPermissionResolver)

    @Bean
    fun getCustomerBookingsUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        bookingRepository: BookingRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = GetCustomerBookingsUseCase(salonRepository, customerRepository, bookingRepository, salonPermissionResolver)

    @Bean
    fun calculateCustomerLifetimeValueUseCase(bookingRepository: BookingRepository, serviceRepository: ServiceRepository) =
        CalculateCustomerLifetimeValueUseCase(bookingRepository, serviceRepository)

    @Bean
    fun createBookingForCustomerUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        createBookingUseCase: CreateBookingUseCase,
        salonPermissionResolver: SalonPermissionResolver,
    ) = CreateBookingForCustomerUseCase(salonRepository, customerRepository, createBookingUseCase, salonPermissionResolver)

    @Bean
    fun ensureCustomerAssociationUseCase(
        salonRepository: SalonRepository,
        customerRepository: CustomerRepository,
        userRepository: UserRepository,
    ) = EnsureCustomerAssociationUseCase(salonRepository, customerRepository, userRepository)
}
