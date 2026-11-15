package ai.rojan.backend.api.config

import ai.rojan.backend.application.payment.GetPaymentTransactionUseCase
import ai.rojan.backend.application.payment.HandlePaymentCallbackUseCase
import ai.rojan.backend.application.payment.InitiatePaymentUseCase
import ai.rojan.backend.application.port.PaymentGatewayPort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.payment.PaymentTransactionRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the framework-free Payment Gateway Step 4 application use cases as Spring beans - same
 * role [BookingUseCaseConfig] already plays for the booking-engine use cases. Only the three
 * read/write use cases Step 5 exposes over HTTP; [ai.rojan.backend.application.payment.ExpirePendingPaymentsUseCase]
 * has no endpoint and is out of this step's scope, so it is deliberately not wired here.
 */
@Configuration
class PaymentUseCaseConfig {

    @Bean
    fun initiatePaymentUseCase(
        bookingRepository: BookingRepository,
        serviceRepository: ServiceRepository,
        paymentTransactionRepository: PaymentTransactionRepository,
        paymentGatewayPort: PaymentGatewayPort,
    ) = InitiatePaymentUseCase(bookingRepository, serviceRepository, paymentTransactionRepository, paymentGatewayPort)

    @Bean
    fun handlePaymentCallbackUseCase(
        paymentTransactionRepository: PaymentTransactionRepository,
        paymentGatewayPort: PaymentGatewayPort,
    ) = HandlePaymentCallbackUseCase(paymentTransactionRepository, paymentGatewayPort)

    @Bean
    fun getPaymentTransactionUseCase(
        paymentTransactionRepository: PaymentTransactionRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = GetPaymentTransactionUseCase(paymentTransactionRepository, salonPermissionResolver)
}
