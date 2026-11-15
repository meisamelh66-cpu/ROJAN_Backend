package ai.rojan.backend.domain.payment

/**
 * Lifecycle of a [PaymentTransaction]. [SUCCEEDED] is the only non-terminal-looking state with a
 * further valid transition ([REFUNDED]) - [FAILED]/[EXPIRED]/[REFUNDED] are fully terminal. See
 * [PaymentTransaction]'s own transition-guard methods for the exact, enforced valid-edge set.
 */
enum class PaymentStatus {
    INITIATED,
    AWAITING_CALLBACK,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    REFUNDED,
}
