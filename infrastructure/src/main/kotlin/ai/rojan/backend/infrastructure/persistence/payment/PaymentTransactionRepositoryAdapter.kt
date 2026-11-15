package ai.rojan.backend.infrastructure.persistence.payment

import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransaction
import ai.rojan.backend.domain.payment.PaymentTransactionId
import ai.rojan.backend.domain.payment.PaymentTransactionRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class PaymentTransactionRepositoryAdapter(
    private val jpaRepository: PaymentTransactionSpringDataRepository,
    private val jdbcTemplate: JdbcTemplate,
) : PaymentTransactionRepository {

    @Transactional
    override fun save(transaction: PaymentTransaction): PaymentTransaction {
        val entity = jpaRepository.findById(transaction.id.value).orElse(null)
            ?.applyMutableFieldsFrom(transaction)
            ?: transaction.toNewEntity()
        return jpaRepository.saveAndFlush(entity).toDomain()
    }

    override fun findById(id: PaymentTransactionId): PaymentTransaction? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByGatewayAuthority(gatewayAuthority: String): PaymentTransaction? =
        jpaRepository.findByGatewayAuthority(gatewayAuthority)?.toDomain()

    override fun findByIdempotencyKey(idempotencyKey: String): PaymentTransaction? =
        jpaRepository.findByIdempotencyKey(idempotencyKey)?.toDomain()

    override fun findExpirable(createdBefore: Instant): List<PaymentTransaction> =
        jpaRepository.findByStatusInAndCreatedAtBefore(
            listOf(PaymentStatus.INITIATED, PaymentStatus.AWAITING_CALLBACK),
            createdBefore,
        ).map { it.toDomain() }

    /**
     * Same real concurrency-serialization tool [ai.rojan.backend.infrastructure.persistence.booking.BookingRepositoryAdapter.reserve]
     * already uses (`pg_advisory_xact_lock`, transaction-scoped, auto-released at commit) — not a new
     * locking framework. The lock is acquired BEFORE the fresh read, so a second concurrent caller for
     * the same [id] blocks until the first's transaction fully commits, then sees the already-updated
     * row - its own [transition] attempt against that fresh state is the real race-safety guarantee
     * ([ai.rojan.backend.domain.common.InvalidPaymentStateException] on a now-invalid transition is
     * expected and left to the caller to handle as a lost race, not swallowed here).
     */
    @Transactional
    override fun updateAtomically(id: PaymentTransactionId, transition: (PaymentTransaction) -> Unit): PaymentTransaction? {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(${id.value.leastSignificantBits})")
        val entity = jpaRepository.findById(id.value).orElse(null) ?: return null
        val domain = entity.toDomain()
        transition(domain)
        return jpaRepository.saveAndFlush(entity.applyMutableFieldsFrom(domain)).toDomain()
    }

    private fun PaymentTransactionJpaEntity.applyMutableFieldsFrom(transaction: PaymentTransaction): PaymentTransactionJpaEntity = apply {
        status = transaction.status
        gatewayProvider = transaction.gatewayProvider
        gatewayAuthority = transaction.gatewayAuthority
        gatewayReferenceId = transaction.gatewayReferenceId
        failureReason = transaction.failureReason
    }

    private fun PaymentTransaction.toNewEntity() = PaymentTransactionJpaEntity(
        id = id.value,
        salonId = salonId.value,
        bookingId = bookingId?.value,
        customerId = customerId.value,
        method = method,
        amount = amount,
        currency = currency,
        status = status,
        gatewayProvider = gatewayProvider,
        gatewayAuthority = gatewayAuthority,
        gatewayReferenceId = gatewayReferenceId,
        idempotencyKey = idempotencyKey,
        failureReason = failureReason,
    )

    private fun PaymentTransactionJpaEntity.toDomain(): PaymentTransaction = PaymentTransaction.reconstitute(
        id = PaymentTransactionId(id),
        salonId = SalonId(salonId),
        customerId = UserId(customerId),
        bookingId = bookingId?.let { BookingId(it) },
        method = method,
        amount = amount,
        currency = currency,
        idempotencyKey = idempotencyKey,
        status = status,
        gatewayProvider = gatewayProvider,
        gatewayAuthority = gatewayAuthority,
        gatewayReferenceId = gatewayReferenceId,
        failureReason = failureReason,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
