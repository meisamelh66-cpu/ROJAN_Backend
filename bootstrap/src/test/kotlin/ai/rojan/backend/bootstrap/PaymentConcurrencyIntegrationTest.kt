package ai.rojan.backend.bootstrap

import ai.rojan.backend.application.payment.ExpirePendingPaymentsUseCase
import ai.rojan.backend.application.payment.HandlePaymentCallbackCommand
import ai.rojan.backend.application.payment.HandlePaymentCallbackUseCase
import ai.rojan.backend.application.payment.InitiatePaymentCommand
import ai.rojan.backend.application.payment.InitiatePaymentUseCase
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransactionRepository
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import ai.rojan.backend.infrastructure.payment.FakePaymentGatewayAdapter
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Payment Gateway Step 4 — real races, real threads, real (embedded, no-Docker) PostgreSQL, real
 * [PaymentTransactionRepository.updateAtomically] advisory-lock serialization. No controller, no
 * Zarinpal — the real [FakePaymentGatewayAdapter] bean stands in for the gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PaymentConcurrencyIntegrationTest {

    @Autowired private lateinit var paymentTransactionRepository: PaymentTransactionRepository
    @Autowired private lateinit var bookingRepository: BookingRepository
    @Autowired private lateinit var salonRepository: SalonRepository
    @Autowired private lateinit var serviceCategoryRepository: ServiceCategoryRepository
    @Autowired private lateinit var serviceRepository: ServiceRepository
    @Autowired private lateinit var specialistRepository: SpecialistRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var gateway: FakePaymentGatewayAdapter

    private fun initiateUseCase() = InitiatePaymentUseCase(bookingRepository, serviceRepository, paymentTransactionRepository, gateway)
    private fun callbackUseCase() = HandlePaymentCallbackUseCase(paymentTransactionRepository, gateway)
    private fun expireUseCase(now: () -> Instant) = ExpirePendingPaymentsUseCase(paymentTransactionRepository, bookingRepository, now)

    private fun realCustomer(): User =
        userRepository.save(User.register(Email("concurrency.${System.nanoTime()}@example.com"), "hash", "Real Customer", UserRole.CUSTOMER))

    private fun realPendingBooking(): Booking {
        val owner = realCustomer()
        val salon = salonRepository.save(Salon.create(owner.id, "Concurrency Salon", null, "09120000000", null, "Tehran"))
        val category = serviceCategoryRepository.save(ServiceCategory.create(salon.id, "Cat", null))
        val service = serviceRepository.save(Service.create(salon.id, category.id, "Haircut", null, 30, BigDecimal("120000")))
        val specialist = specialistRepository.save(Specialist.create(salon.id, null, "Specialist", null, null))
        val customer = realCustomer()
        val booking = Booking.create(
            salonId = salon.id, serviceId = service.id, specialistId = specialist.id, customerId = customer.id,
            startTime = LocalDateTime.of(2026, 12, 1, 10, 0), endTime = LocalDateTime.of(2026, 12, 1, 10, 30), notes = null,
        )
        return bookingRepository.reserve(booking)
    }

    private fun runConcurrently(count: Int, block: () -> Unit) {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val done = CountDownLatch(count)
        val pool = Executors.newFixedThreadPool(count)
        repeat(count) {
            pool.execute {
                ready.countDown()
                start.await()
                try {
                    block()
                } finally {
                    done.countDown()
                }
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent calls did not complete in time")
        pool.shutdown()
    }

    // ---------------- A — duplicate initiate ----------------

    @Test
    fun `race A - two concurrent initiate calls with the same idempotency key produce exactly one transaction and one real gateway call`() {
        val booking = realPendingBooking()
        val key = "race-a-${booking.id.value}"
        val initiateCallsBefore = gateway.initiateCallCount
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())

        runConcurrently(2) {
            val result = initiateUseCase().execute(InitiatePaymentCommand(booking.customerId, booking.id, key, "https://cb"))
            results.add("${result.transactionId}|${result.authority}|${result.redirectUrl}")
        }

        assertEquals(1, gateway.initiateCallCount - initiateCallsBefore, "exactly one real gateway initiation must happen under a true concurrent race")
        assertEquals(1, results.toSet().size, "both concurrent callers must receive the identical result")
        val stored = paymentTransactionRepository.findByIdempotencyKey(key)
        assertEquals(PaymentStatus.AWAITING_CALLBACK, stored?.status)
    }

    // ---------------- B — duplicate callback ----------------

    @Test
    fun `race B - two concurrent callbacks for the same authority produce exactly one real verify call and one terminal state`() {
        val booking = realPendingBooking()
        val initiation = initiateUseCase().execute(InitiatePaymentCommand(booking.customerId, booking.id, "race-b-${booking.id.value}", "https://cb"))
        val verifyCallsBefore = gateway.verifyCallCount

        runConcurrently(2) {
            callbackUseCase().execute(HandlePaymentCallbackCommand(initiation.authority))
        }

        assertEquals(1, gateway.verifyCallCount - verifyCallsBefore, "exactly one real verify call must happen under a true concurrent duplicate callback")
        val final = paymentTransactionRepository.findById(initiation.transactionId)!!
        assertEquals(PaymentStatus.SUCCEEDED, final.status)
    }

    // ---------------- C — callback vs expiry ----------------

    @Test
    fun `race C - a concurrent real success callback and expiry sweep for the same transaction never corrupt state - exactly one terminal status wins`() {
        val booking = realPendingBooking()
        val initiation = initiateUseCase().execute(InitiatePaymentCommand(booking.customerId, booking.id, "race-c-${booking.id.value}", "https://cb"))
        val realCreatedAt = paymentTransactionRepository.findById(initiation.transactionId)!!.createdAt
        val pastHoldWindow = { realCreatedAt.plus(ExpirePendingPaymentsUseCase.HOLD_DURATION).plusSeconds(5) }

        runConcurrently(2) {
            // Whichever of these two acquires updateAtomically's advisory lock first wins; the other sees
            // the already-mutated fresh state and its own guard makes it a no-op - that is the behavior
            // under test, not a specific winner (real thread scheduling decides which one wins each run).
            try {
                callbackUseCase().execute(HandlePaymentCallbackCommand(initiation.authority))
            } catch (ignored: Exception) {
                // acceptable if expiry already cancelled the booking/transaction first in this particular run
            }
            try {
                expireUseCase(pastHoldWindow).execute()
            } catch (ignored: Exception) {
            }
        }

        val final = paymentTransactionRepository.findById(initiation.transactionId)!!
        assertTrue(
            final.status == PaymentStatus.SUCCEEDED || final.status == PaymentStatus.EXPIRED,
            "final status must be exactly one real terminal outcome, never a corrupted/invalid state - was ${final.status}",
        )
        val finalBooking = bookingRepository.findById(booking.id)!!
        when (final.status) {
            PaymentStatus.EXPIRED -> assertEquals(BookingStatus.CANCELLED, finalBooking.status)
            PaymentStatus.SUCCEEDED -> assertEquals(BookingStatus.PENDING, finalBooking.status)
            else -> error("unreachable given the assertion above")
        }
    }
}
