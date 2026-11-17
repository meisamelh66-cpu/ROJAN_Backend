package ai.rojan.backend.domain.common

/** Payment Gateway Step 1 — mirrors [InvalidBookingStateException]'s own role for [ai.rojan.backend.domain.payment.PaymentTransaction]'s status-transition guards. A new file (not [BookingDomainExceptions.kt]) despite [DomainException] being `sealed`: same package satisfies Kotlin's sealed-class rule without implying this is a Booking concept. */
class InvalidPaymentStateException(message: String) : DomainException(message)

/** Payment Gateway Step 4. */
class PaymentTransactionNotFoundException(identifier: String) :
    DomainException("Payment transaction not found: $identifier")

/** Payment Gateway Step 4 — the booking exists and belongs to the caller, but isn't in a state that can be paid for (e.g. already CANCELLED/COMPLETED). */
class BookingNotEligibleForPaymentException(bookingId: String, reason: String) :
    DomainException("Booking $bookingId is not eligible for payment: $reason")

/** Payment Gateway Step 4 — an idempotency key presented by a different customer than the one who first used it. Denied outright, never replayed, never silently given a fresh transaction under the same key. */
class IdempotencyKeyOwnedByAnotherCustomerException(key: String) :
    DomainException("Idempotency key $key belongs to a different customer")

/** Payment Gateway Step 4 — a callback referencing a gateway authority this backend never issued. */
class UnknownPaymentAuthorityException(authority: String) :
    DomainException("No payment transaction found for gateway authority: $authority")

/** P1 Per-Salon Payment Gateway Configuration — a real gateway call (initiate/verify) was attempted for a salon with no active [ai.rojan.backend.domain.payment.PaymentGatewayConfig]. Never a fallback to any global credential. */
class PaymentGatewayNotConfiguredException(salonId: String) :
    DomainException("Salon $salonId has no active payment gateway configuration")

/** P1 Per-Salon Payment Gateway Configuration — activate/deactivate/test attempted for a salon that has never saved a [ai.rojan.backend.domain.payment.PaymentGatewayConfig] at all (distinct from one saved but inactive). */
class PaymentGatewayConfigNotFoundException(salonId: String) :
    DomainException("Salon $salonId has no payment gateway configuration saved yet")
