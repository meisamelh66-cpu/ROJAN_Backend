package ai.rojan.backend.domain.payment

import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.common.InvalidPaymentStateException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private fun newTransaction(
    amount: Long = 100_000,
    bookingId: BookingId? = BookingId.new(),
    idempotencyKey: String? = "idem-key-1",
): PaymentTransaction = PaymentTransaction.create(
    salonId = SalonId.new(),
    customerId = UserId.new(),
    bookingId = bookingId,
    amount = amount,
    method = PaymentTransactionMethod.ONLINE_GATEWAY,
    idempotencyKey = idempotencyKey,
)

class PaymentTransactionTest {

    // ---- creation invariants ----

    @Test
    fun `is created as INITIATED`() {
        val transaction = newTransaction()
        assertEquals(PaymentStatus.INITIATED, transaction.status)
    }

    @Test
    fun `defaults currency to IRR`() {
        val transaction = newTransaction()
        assertEquals("IRR", transaction.currency)
    }

    @Test
    fun `rejects a non-positive amount`() {
        assertThrows(IllegalArgumentException::class.java) {
            newTransaction(amount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            newTransaction(amount = -100)
        }
    }

    @Test
    fun `rejects a currency other than IRR`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentTransaction.create(
                salonId = SalonId.new(),
                customerId = UserId.new(),
                bookingId = null,
                amount = 100_000,
                currency = "USD",
                method = PaymentTransactionMethod.ONLINE_GATEWAY,
                idempotencyKey = null,
            )
        }
    }

    @Test
    fun `bookingId may be null`() {
        val transaction = newTransaction(bookingId = null)
        assertNull(transaction.bookingId)
    }

    @Test
    fun `gatewayAuthority and gatewayReferenceId are null until the gateway supplies them`() {
        val transaction = newTransaction()
        assertNull(transaction.gatewayAuthority)
        assertNull(transaction.gatewayReferenceId)
    }

    @Test
    fun `idempotencyKey is stored as-is - uniqueness is a DB-layer concern, not enforced here`() {
        val first = newTransaction(idempotencyKey = "same-key")
        val second = newTransaction(idempotencyKey = "same-key")
        assertEquals("same-key", first.idempotencyKey)
        assertEquals("same-key", second.idempotencyKey)
    }

    // ---- valid transitions (every edge in the spec) ----

    @Test
    fun `INITIATED to AWAITING_CALLBACK stores the opaque gateway provider and authority`() {
        val transaction = newTransaction()
        transaction.markAwaitingCallback(gatewayProvider = "ZARINPAL", gatewayAuthority = "A00000000000000000000000000000000000")
        assertEquals(PaymentStatus.AWAITING_CALLBACK, transaction.status)
        assertEquals("ZARINPAL", transaction.gatewayProvider)
        assertEquals("A00000000000000000000000000000000000", transaction.gatewayAuthority)
    }

    @Test
    fun `INITIATED to FAILED`() {
        val transaction = newTransaction()
        transaction.markFailed("gateway unreachable")
        assertEquals(PaymentStatus.FAILED, transaction.status)
        assertEquals("gateway unreachable", transaction.failureReason)
    }

    @Test
    fun `INITIATED to EXPIRED`() {
        val transaction = newTransaction()
        transaction.markExpired()
        assertEquals(PaymentStatus.EXPIRED, transaction.status)
    }

    @Test
    fun `AWAITING_CALLBACK to VERIFYING`() {
        val transaction = awaitingCallbackTransaction()
        transaction.markVerifying()
        assertEquals(PaymentStatus.VERIFYING, transaction.status)
    }

    @Test
    fun `AWAITING_CALLBACK to FAILED`() {
        val transaction = awaitingCallbackTransaction()
        transaction.markFailed("gateway reported NOK")
        assertEquals(PaymentStatus.FAILED, transaction.status)
    }

    @Test
    fun `AWAITING_CALLBACK to EXPIRED`() {
        val transaction = awaitingCallbackTransaction()
        transaction.markExpired()
        assertEquals(PaymentStatus.EXPIRED, transaction.status)
    }

    @Test
    fun `VERIFYING to SUCCEEDED stores the opaque gateway reference id`() {
        val transaction = verifyingTransaction()
        transaction.markSucceeded(gatewayReferenceId = "REF-123456")
        assertEquals(PaymentStatus.SUCCEEDED, transaction.status)
        assertEquals("REF-123456", transaction.gatewayReferenceId)
    }

    @Test
    fun `VERIFYING to FAILED`() {
        val transaction = verifyingTransaction()
        transaction.markFailed("amount mismatch")
        assertEquals(PaymentStatus.FAILED, transaction.status)
    }

    @Test
    fun `SUCCEEDED to REFUNDED`() {
        val transaction = succeededTransaction()
        transaction.markRefunded()
        assertEquals(PaymentStatus.REFUNDED, transaction.status)
    }

    // ---- invalid transitions (every case the spec explicitly forbids) ----

    @Test
    fun `terminal FAILED never transitions to anything`() {
        val transaction = newTransaction()
        transaction.markFailed("x")
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markAwaitingCallback("ZARINPAL", "auth") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markVerifying() }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markSucceeded("ref") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markExpired() }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markRefunded() }
    }

    @Test
    fun `terminal EXPIRED never transitions to anything`() {
        val transaction = newTransaction()
        transaction.markExpired()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markAwaitingCallback("ZARINPAL", "auth") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markVerifying() }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markSucceeded("ref") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markFailed("x") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markRefunded() }
    }

    @Test
    fun `terminal REFUNDED never transitions to anything`() {
        val transaction = succeededTransaction()
        transaction.markRefunded()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markAwaitingCallback("ZARINPAL", "auth") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markVerifying() }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markSucceeded("ref") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markFailed("x") }
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markExpired() }
    }

    @Test
    fun `SUCCEEDED does not transition to FAILED`() {
        val transaction = succeededTransaction()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markFailed("too late") }
    }

    @Test
    fun `SUCCEEDED does not transition to EXPIRED`() {
        val transaction = succeededTransaction()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markExpired() }
    }

    @Test
    fun `SUCCEEDED does not transition to VERIFYING`() {
        val transaction = succeededTransaction()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markVerifying() }
    }

    @Test
    fun `AWAITING_CALLBACK does not transition directly to SUCCEEDED`() {
        val transaction = awaitingCallbackTransaction()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markSucceeded("ref") }
    }

    @Test
    fun `INITIATED does not transition directly to SUCCEEDED`() {
        val transaction = newTransaction()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markSucceeded("ref") }
    }

    @Test
    fun `VERIFYING does not transition directly to REFUNDED`() {
        val transaction = verifyingTransaction()
        assertThrows(InvalidPaymentStateException::class.java) { transaction.markRefunded() }
    }

    // ---- fixtures for non-initial states ----

    private fun awaitingCallbackTransaction(): PaymentTransaction =
        newTransaction().apply { markAwaitingCallback("ZARINPAL", "auth-token") }

    private fun verifyingTransaction(): PaymentTransaction =
        awaitingCallbackTransaction().apply { markVerifying() }

    private fun succeededTransaction(): PaymentTransaction =
        verifyingTransaction().apply { markSucceeded("REF-1") }
}
