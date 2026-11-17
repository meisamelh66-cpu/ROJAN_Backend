package ai.rojan.backend.application.payment

import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.BookingAccessDeniedException
import ai.rojan.backend.domain.common.BookingNotEligibleForPaymentException
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.IdempotencyKeyOwnedByAnotherCustomerException
import ai.rojan.backend.domain.common.InvalidPaymentStateException
import ai.rojan.backend.domain.common.PaymentTransactionNotFoundException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.UnknownPaymentAuthorityException
import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransaction
import ai.rojan.backend.domain.payment.PaymentTransactionId
import ai.rojan.backend.domain.payment.PaymentTransactionMethod
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

class PaymentUseCasesTest {

    private val bookingRepository = InMemoryBookingRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val salonRepository = InMemorySalonRepository()
    private val paymentTransactionRepository = InMemoryPaymentTransactionRepository()
    private val gateway = ScriptedPaymentGatewayPort()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, InMemorySalonMembershipRepository(), InMemorySpecialistRepository())

    private val customerId = UserId.new()
    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId = ownerId, name = "Real Salon", description = null, phone = "09120000000", email = null, address = "Tehran")
    private val service = Service.create(salonId = salon.id, categoryId = ServiceCategoryId.new(), name = "Haircut", description = null, durationMinutes = 30, price = BigDecimal("150000"))

    init {
        salonRepository.save(salon)
        serviceRepository.save(service)
    }

    private fun pendingBooking(customer: UserId = customerId): Booking {
        val booking = Booking.create(
            salonId = salon.id,
            serviceId = service.id,
            specialistId = SpecialistId.new(),
            customerId = customer,
            startTime = LocalDateTime.of(2026, 12, 1, 10, 0),
            endTime = LocalDateTime.of(2026, 12, 1, 10, 30),
            notes = null,
        )
        bookingRepository.reserve(booking)
        return booking
    }

    private fun initiateUseCase() = InitiatePaymentUseCase(bookingRepository, serviceRepository, paymentTransactionRepository, gateway)
    private fun callbackUseCase() = HandlePaymentCallbackUseCase(paymentTransactionRepository, gateway)
    private fun getUseCase() = GetPaymentTransactionUseCase(paymentTransactionRepository, salonPermissionResolver)
    private fun expireUseCase(now: () -> Instant = Instant::now) = ExpirePendingPaymentsUseCase(paymentTransactionRepository, bookingRepository, now)

    // ---------------- InitiatePaymentUseCase ----------------

    @Test
    fun `initiate rejects a booking that does not belong to the caller`() {
        val booking = pendingBooking(customer = UserId.new())
        val command = InitiatePaymentCommand(customerId, booking.id, "key-1", "https://cb")

        assertThrows(BookingAccessDeniedException::class.java) { initiateUseCase().execute(command) }
    }

    @Test
    fun `initiate rejects a booking that is not PENDING`() {
        val booking = pendingBooking()
        booking.confirm()
        bookingRepository.save(booking)

        assertThrows(BookingNotEligibleForPaymentException::class.java) {
            initiateUseCase().execute(InitiatePaymentCommand(customerId, booking.id, "key-2", "https://cb"))
        }
    }

    @Test
    fun `initiate rejects an unknown booking`() {
        assertThrows(BookingNotFoundException::class.java) {
            initiateUseCase().execute(InitiatePaymentCommand(customerId, BookingId.new(), "key-3", "https://cb"))
        }
    }

    @Test
    fun `initiate takes the amount from the real service price, never a client-supplied value`() {
        val booking = pendingBooking()

        initiateUseCase().execute(InitiatePaymentCommand(customerId, booking.id, "key-4", "https://cb"))

        assertEquals(150_000L, paymentTransactionRepository.findByIdempotencyKey("key-4")!!.amount)
    }

    @Test
    fun `initiate creates a transaction, calls the gateway, stores the authority, and reaches AWAITING_CALLBACK`() {
        val booking = pendingBooking()

        val result = initiateUseCase().execute(InitiatePaymentCommand(customerId, booking.id, "key-5", "https://cb"))

        assertEquals(1, gateway.initiateCallCount)
        val stored = paymentTransactionRepository.findById(result.transactionId)!!
        assertEquals(PaymentStatus.AWAITING_CALLBACK, stored.status)
        assertEquals(result.authority, stored.gatewayAuthority)
        assertTrue(result.redirectUrl.isNotBlank())
    }

    @Test
    fun `replaying the same idempotency key for the same customer never creates a second transaction or a second gateway call`() {
        val booking = pendingBooking()
        val command = InitiatePaymentCommand(customerId, booking.id, "key-6", "https://cb")

        val first = initiateUseCase().execute(command)
        val second = initiateUseCase().execute(command)

        assertEquals(1, gateway.initiateCallCount)
        assertEquals(first.transactionId, second.transactionId)
        assertEquals(first.authority, second.authority)
        assertEquals(first.redirectUrl, second.redirectUrl)
    }

    @Test
    fun `a different customer presenting the same idempotency key is denied, never replayed and never given an independent transaction`() {
        val booking = pendingBooking()
        initiateUseCase().execute(InitiatePaymentCommand(customerId, booking.id, "shared-key", "https://cb"))

        val otherCustomer = UserId.new()
        val otherBooking = pendingBooking(customer = otherCustomer)

        assertThrows(IdempotencyKeyOwnedByAnotherCustomerException::class.java) {
            initiateUseCase().execute(InitiatePaymentCommand(otherCustomer, otherBooking.id, "shared-key", "https://cb"))
        }
    }

    // ---------------- HandlePaymentCallbackUseCase ----------------

    private fun awaitingCallbackTransaction(amount: Long = 100_000): PaymentTransaction {
        val booking = pendingBooking()
        val result = initiateUseCase().execute(InitiatePaymentCommand(customerId, booking.id, "cb-key-${booking.id.value}", "https://cb"))
        return paymentTransactionRepository.findById(result.transactionId)!!
    }

    @Test
    fun `callback on a real success verification moves AWAITING_CALLBACK to VERIFYING to SUCCEEDED and stores the real reference`() {
        val transaction = awaitingCallbackTransaction()

        val result = callbackUseCase().execute(HandlePaymentCallbackCommand(transaction.gatewayAuthority!!))

        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertNotNull(result.gatewayReferenceId)
        assertEquals(null, result.failureReason)
    }

    @Test
    fun `callback on a real failure verification moves AWAITING_CALLBACK to VERIFYING to FAILED and stores the real reason, never a reference`() {
        val transaction = awaitingCallbackTransaction()
        gateway.scriptVerificationFailure(transaction.gatewayAuthority!!, "card declined")

        val result = callbackUseCase().execute(HandlePaymentCallbackCommand(transaction.gatewayAuthority!!))

        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals("card declined", result.failureReason)
        assertEquals(null, result.gatewayReferenceId)
    }

    @Test
    fun `a callback replay on an already-terminal transaction is a no-op, never a second mutation`() {
        val transaction = awaitingCallbackTransaction()
        val first = callbackUseCase().execute(HandlePaymentCallbackCommand(transaction.gatewayAuthority!!))
        assertEquals(PaymentStatus.SUCCEEDED, first.status)

        val replay = callbackUseCase().execute(HandlePaymentCallbackCommand(transaction.gatewayAuthority!!))

        assertEquals(PaymentStatus.SUCCEEDED, replay.status)
        assertEquals(first.gatewayReferenceId, replay.gatewayReferenceId)
    }

    @Test
    fun `a callback for an unknown authority is a clear domain error, not a silent success`() {
        assertThrows(UnknownPaymentAuthorityException::class.java) {
            callbackUseCase().execute(HandlePaymentCallbackCommand("never-issued-authority"))
        }
    }

    @Test
    fun `a real amount mismatch between what was initiated and the transaction's own stored amount never reaches SUCCEEDED`() {
        // Deliberately constructs a transaction whose own stored amount differs from what the gateway
        // actually recorded at initiate time - the real, concrete shape an amount-mismatch takes.
        val mismatchedAmount = 999_999L
        val authority = gateway.initiate(100_000, "x", "https://cb", salon.id).authority
        val transaction = PaymentTransaction.reconstitute(
            id = PaymentTransactionId.new(),
            salonId = salon.id,
            customerId = customerId,
            bookingId = null,
            method = PaymentTransactionMethod.ONLINE_GATEWAY,
            amount = mismatchedAmount,
            currency = "IRR",
            idempotencyKey = null,
            status = PaymentStatus.AWAITING_CALLBACK,
            gatewayProvider = "SCRIPTED",
            gatewayAuthority = authority,
            gatewayReferenceId = null,
            failureReason = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        paymentTransactionRepository.save(transaction)

        val result = callbackUseCase().execute(HandlePaymentCallbackCommand(authority))

        assertEquals(PaymentStatus.FAILED, result.status)
        assertNotNull(result.failureReason)
    }

    // ---------------- GetPaymentTransactionUseCase ----------------

    @Test
    fun `the paying customer can read their own transaction`() {
        val transaction = awaitingCallbackTransaction()

        val found = getUseCase().execute(transaction.id, customerId)

        assertEquals(transaction.id, found.id)
    }

    @Test
    fun `the salon owner can read a transaction for their own salon`() {
        val transaction = awaitingCallbackTransaction()

        val found = getUseCase().execute(transaction.id, ownerId)

        assertEquals(transaction.id, found.id)
    }

    @Test
    fun `a different customer cannot read someone else's transaction`() {
        val transaction = awaitingCallbackTransaction()

        assertThrows(SalonAccessDeniedException::class.java) { getUseCase().execute(transaction.id, UserId.new()) }
    }

    @Test
    fun `an owner of a different salon cannot read this transaction`() {
        val transaction = awaitingCallbackTransaction()
        val otherOwner = UserId.new()
        salonRepository.save(Salon.create(ownerId = otherOwner, name = "Other Salon", description = null, phone = "09121111111", email = null, address = "Mashhad"))

        assertThrows(SalonAccessDeniedException::class.java) { getUseCase().execute(transaction.id, otherOwner) }
    }

    @Test
    fun `a nonexistent transaction id is a clear not-found error`() {
        assertThrows(PaymentTransactionNotFoundException::class.java) { getUseCase().execute(PaymentTransactionId.new(), customerId) }
    }

    // ---------------- ExpirePendingPaymentsUseCase ----------------

    @Test
    fun `an AWAITING_CALLBACK transaction past its hold window is expired and its still-PENDING booking is cancelled`() {
        val booking = pendingBooking()
        val transaction = awaitingCallbackTransactionFor(booking)
        val future = { Instant.now().plus(ExpirePendingPaymentsUseCase.HOLD_DURATION).plusSeconds(1) }

        val expired = expireUseCase(future).execute()

        assertEquals(1, expired.size)
        assertEquals(PaymentStatus.EXPIRED, expired.first().status)
        assertEquals(BookingStatus.CANCELLED, bookingRepository.findById(booking.id)!!.status)
    }

    @Test
    fun `a transaction still within its hold window is never expired`() {
        val booking = pendingBooking()
        awaitingCallbackTransactionFor(booking)

        val expired = expireUseCase { Instant.now() }.execute()

        assertTrue(expired.isEmpty())
        assertEquals(BookingStatus.PENDING, bookingRepository.findById(booking.id)!!.status)
    }

    @Test
    fun `a SUCCEEDED transaction is never expired even if its creation time is long past`() {
        val booking = pendingBooking()
        val transaction = awaitingCallbackTransactionFor(booking)
        callbackUseCase().execute(HandlePaymentCallbackCommand(transaction.gatewayAuthority!!))
        val future = { Instant.now().plus(ExpirePendingPaymentsUseCase.HOLD_DURATION).plusSeconds(1) }

        val expired = expireUseCase(future).execute()

        assertTrue(expired.isEmpty())
        assertEquals(BookingStatus.PENDING, bookingRepository.findById(booking.id)!!.status)
    }

    @Test
    fun `an already-EXPIRED transaction is never re-expired or re-mutated`() {
        val booking = pendingBooking()
        awaitingCallbackTransactionFor(booking)
        val future = { Instant.now().plus(ExpirePendingPaymentsUseCase.HOLD_DURATION).plusSeconds(1) }
        val firstPass = expireUseCase(future).execute()
        assertEquals(1, firstPass.size)

        val secondPass = expireUseCase(future).execute()

        assertTrue(secondPass.isEmpty())
    }

    @Test
    fun `calling markExpired on an already-SUCCEEDED transaction directly still throws - the domain guard itself never silently allows it`() {
        val transaction = PaymentTransaction.create(
            salonId = salon.id, customerId = customerId, bookingId = null, amount = 1,
            method = PaymentTransactionMethod.ONLINE_GATEWAY, idempotencyKey = null,
        )
        transaction.markAwaitingCallback("SCRIPTED", "auth-x")
        transaction.markVerifying()
        transaction.markSucceeded("ref-x")

        assertThrows(InvalidPaymentStateException::class.java) { transaction.markExpired() }
    }

    private fun awaitingCallbackTransactionFor(booking: Booking): PaymentTransaction {
        val result = initiateUseCase().execute(InitiatePaymentCommand(booking.customerId, booking.id, "exp-key-${booking.id.value}", "https://cb"))
        return paymentTransactionRepository.findById(result.transactionId)!!
    }
}
