package ai.rojan.backend.domain.booking

/**
 * How the customer chose to pay for a booking. Names are the canonical
 * backend representation of this concept and must stay identical to the
 * Android client's `ai.rojan.designlab.domain.booking.PaymentMethod`
 * (the request/response JSON carries the enum name verbatim).
 */
enum class PaymentMethod {
    WALLET,
    PAY_AT_SALON,
}
