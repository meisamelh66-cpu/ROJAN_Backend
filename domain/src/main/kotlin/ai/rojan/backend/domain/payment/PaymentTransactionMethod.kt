package ai.rojan.backend.domain.payment

/**
 * How a [PaymentTransaction] is settled — deliberately independent of
 * [ai.rojan.backend.domain.booking.PaymentMethod] (that enum describes a customer's booking-time
 * payment *intent*; this one describes how this specific transaction is actually processed). Kept
 * separate per the approved Payment Gateway design so `domain.payment` has zero dependency on
 * `domain.booking` - a transaction may exist independent of any booking at all ([PaymentTransaction.bookingId]
 * is nullable).
 */
enum class PaymentTransactionMethod {
    ONLINE_GATEWAY,
    WALLET,
    PAY_AT_SALON,
}
