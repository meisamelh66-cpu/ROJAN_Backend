package ai.rojan.backend.domain.payment

import java.time.Instant

interface PaymentTransactionRepository {
    /** Upsert by [PaymentTransaction.id] - same create-or-update shape as [ai.rojan.backend.domain.booking.BookingRepository.save]. */
    fun save(transaction: PaymentTransaction): PaymentTransaction

    fun findById(id: PaymentTransactionId): PaymentTransaction?

    /** The lookup the real callback flow (Step 4+) will key off - included now so the DB-level unique constraint on this column can be proven at the repository layer in Step 2. */
    fun findByGatewayAuthority(gatewayAuthority: String): PaymentTransaction?

    /** Payment Gateway Step 4 — the replay/ownership lookup `InitiatePaymentUseCase` keys off; relies on the real, DB-enforced unique index on this column (Step 2) for its concurrency guarantee, not application-level locking. */
    fun findByIdempotencyKey(idempotencyKey: String): PaymentTransaction?

    /**
     * Payment Gateway Step 4 — transactions still in [PaymentStatus.INITIATED] or [PaymentStatus.AWAITING_CALLBACK]
     * whose hold window (computed from [PaymentTransaction.createdAt] by the caller's own policy, not a stored
     * deadline — no `expiresAt` column exists) has passed [createdBefore].
     */
    fun findExpirable(createdBefore: Instant): List<PaymentTransaction>

    /**
     * Payment Gateway Step 4 — the single, minimal concurrency primitive every mutating use case (initiate's
     * completion, callback handling, expiry) goes through: serializes concurrent callers for the same [id] via a
     * Postgres transaction-scoped advisory lock (same real mechanism [ai.rojan.backend.domain.booking.BookingRepository.reserve]
     * already uses for specialist-booking conflicts - not a new locking framework), re-reads the row fresh under
     * that lock, applies [transition] (expected to call this transaction's own guarded `mark*` methods - a
     * transition that's no longer valid given the fresh state is expected to be a silent no-op or to let
     * [InvalidPaymentStateException] propagate, the caller's choice), then persists. Returns `null` if [id]
     * doesn't exist.
     */
    fun updateAtomically(id: PaymentTransactionId, transition: (PaymentTransaction) -> Unit): PaymentTransaction?
}
