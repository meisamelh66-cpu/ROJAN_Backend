package ai.rojan.backend.bootstrap

import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransaction
import ai.rojan.backend.domain.payment.PaymentTransactionMethod
import ai.rojan.backend.domain.payment.PaymentTransactionRepository
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Payment Gateway Step 2 — real (embedded, no-Docker) PostgreSQL persistence tests for
 * [PaymentTransactionRepository]. No controller, no use case, no Zarinpal - only the
 * repository/JPA/migration layer, same evidentiary bar `BookingEngineFlowIntegrationTest`
 * already sets for the booking-engine vertical, scoped down to repository-only calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PaymentTransactionRepositoryAdapterTest {

    @Autowired
    private lateinit var paymentTransactionRepository: PaymentTransactionRepository

    @Autowired
    private lateinit var salonRepository: SalonRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private fun realCustomer(): UserId {
        val user = User.register(
            email = Email("payment.${System.nanoTime()}@example.com"),
            passwordHash = "hash",
            fullName = "Real Payment Customer",
            role = UserRole.CUSTOMER,
        )
        return userRepository.save(user).id
    }

    private fun realSalon(ownerId: UserId): SalonId {
        val salon = Salon.create(
            ownerId = ownerId,
            name = "Real Payment Salon",
            description = null,
            phone = "09120000000",
            email = null,
            address = "Tehran",
        )
        return salonRepository.save(salon).id
    }

    private fun newTransaction(
        salonId: SalonId,
        customerId: UserId,
        amount: Long = 150_000,
        idempotencyKey: String? = "idem-${System.nanoTime()}",
    ): PaymentTransaction = PaymentTransaction.create(
        salonId = salonId,
        customerId = customerId,
        bookingId = null,
        amount = amount,
        method = PaymentTransactionMethod.ONLINE_GATEWAY,
        idempotencyKey = idempotencyKey,
    )

    // ---- create / save / read, every field round-trips ----

    @Test
    fun `save persists a real transaction and findById reloads every field without loss`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val transaction = newTransaction(salonId, customerId, amount = 250_000, idempotencyKey = "idem-roundtrip")

        val saved = paymentTransactionRepository.save(transaction)
        val reloaded = paymentTransactionRepository.findById(saved.id)

        assertNotNull(reloaded)
        assertEquals(saved.id, reloaded!!.id)
        assertEquals(salonId, reloaded.salonId)
        assertEquals(customerId, reloaded.customerId)
        assertNull(reloaded.bookingId)
        assertEquals(PaymentTransactionMethod.ONLINE_GATEWAY, reloaded.method)
        assertEquals(250_000L, reloaded.amount)
        assertEquals("IRR", reloaded.currency)
        assertEquals(PaymentStatus.INITIATED, reloaded.status)
        assertEquals("idem-roundtrip", reloaded.idempotencyKey)
        assertNull(reloaded.gatewayProvider)
        assertNull(reloaded.gatewayAuthority)
        assertNull(reloaded.gatewayReferenceId)
        assertNull(reloaded.failureReason)
        assertNotNull(reloaded.createdAt)
    }

    @Test
    fun `findById returns null for a transaction that was never saved`() {
        val transaction = PaymentTransaction.reconstitute(
            id = ai.rojan.backend.domain.payment.PaymentTransactionId.new(),
            salonId = SalonId.new(),
            customerId = UserId.new(),
            bookingId = null,
            method = PaymentTransactionMethod.PAY_AT_SALON,
            amount = 1,
            currency = "IRR",
            idempotencyKey = null,
            status = PaymentStatus.INITIATED,
            gatewayProvider = null,
            gatewayAuthority = null,
            gatewayReferenceId = null,
            failureReason = null,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        assertNull(paymentTransactionRepository.findById(transaction.id))
    }

    // ---- update / status transitions genuinely persist ----

    @Test
    fun `a status transition is saved and reload reflects the new status, not the old one`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val transaction = newTransaction(salonId, customerId)
        val saved = paymentTransactionRepository.save(transaction)

        saved.markAwaitingCallback(gatewayProvider = "ZARINPAL", gatewayAuthority = "auth-${System.nanoTime()}")
        paymentTransactionRepository.save(saved)

        val reloaded = paymentTransactionRepository.findById(saved.id)!!
        assertEquals(PaymentStatus.AWAITING_CALLBACK, reloaded.status)
        assertEquals("ZARINPAL", reloaded.gatewayProvider)
        assertNotNull(reloaded.gatewayAuthority)
    }

    @Test
    fun `markFailed's failureReason genuinely persists`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val transaction = newTransaction(salonId, customerId)
        val saved = paymentTransactionRepository.save(transaction)

        saved.markFailed("gateway unreachable")
        paymentTransactionRepository.save(saved)

        val reloaded = paymentTransactionRepository.findById(saved.id)!!
        assertEquals(PaymentStatus.FAILED, reloaded.status)
        assertEquals("gateway unreachable", reloaded.failureReason)
    }

    // ---- findByGatewayAuthority ----

    @Test
    fun `findByGatewayAuthority finds a real persisted transaction by its real authority`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val transaction = newTransaction(salonId, customerId)
        val authority = "auth-lookup-${System.nanoTime()}"
        transaction.markAwaitingCallback("ZARINPAL", authority)
        paymentTransactionRepository.save(transaction)

        val found = paymentTransactionRepository.findByGatewayAuthority(authority)

        assertNotNull(found)
        assertEquals(transaction.id, found!!.id)
    }

    // ---- DB-enforced uniqueness (not application-level) ----

    @Test
    fun `the DB rejects two transactions with the same real gateway authority`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val sharedAuthority = "shared-auth-${System.nanoTime()}"

        val first = newTransaction(salonId, customerId, idempotencyKey = "idem-a-${System.nanoTime()}")
        first.markAwaitingCallback("ZARINPAL", sharedAuthority)
        paymentTransactionRepository.save(first)

        val second = newTransaction(salonId, customerId, idempotencyKey = "idem-b-${System.nanoTime()}")
        second.markAwaitingCallback("ZARINPAL", sharedAuthority)

        assertThrows(DataIntegrityViolationException::class.java) {
            paymentTransactionRepository.save(second)
        }
    }

    @Test
    fun `the DB rejects two transactions with the same real idempotencyKey`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val sharedKey = "shared-idem-${System.nanoTime()}"

        paymentTransactionRepository.save(newTransaction(salonId, customerId, idempotencyKey = sharedKey))
        val second = newTransaction(salonId, customerId, idempotencyKey = sharedKey)

        assertThrows(DataIntegrityViolationException::class.java) {
            paymentTransactionRepository.save(second)
        }
    }

    @Test
    fun `two transactions with a null gatewayAuthority and null idempotencyKey do not conflict with each other`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)

        val first = paymentTransactionRepository.save(newTransaction(salonId, customerId, idempotencyKey = null))
        val second = paymentTransactionRepository.save(newTransaction(salonId, customerId, idempotencyKey = null))

        assertNotNull(paymentTransactionRepository.findById(first.id))
        assertNotNull(paymentTransactionRepository.findById(second.id))
    }

    // ---- DB-enforced foreign-key integrity ----

    @Test
    fun `the DB rejects a transaction referencing a salonId that does not exist`() {
        val customerId = realCustomer()
        val transaction = newTransaction(salonId = SalonId.new(), customerId = customerId)

        assertThrows(DataIntegrityViolationException::class.java) {
            paymentTransactionRepository.save(transaction)
        }
    }

    @Test
    fun `the DB rejects a transaction referencing a customerId that does not exist`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val transaction = newTransaction(salonId = salonId, customerId = UserId.new())

        assertThrows(DataIntegrityViolationException::class.java) {
            paymentTransactionRepository.save(transaction)
        }
    }

    @Test
    fun `the DB rejects a transaction referencing a bookingId that does not exist`() {
        val customerId = realCustomer()
        val salonId = realSalon(customerId)
        val transaction = PaymentTransaction.reconstitute(
            id = ai.rojan.backend.domain.payment.PaymentTransactionId.new(),
            salonId = salonId,
            customerId = customerId,
            bookingId = ai.rojan.backend.domain.booking.BookingId.new(),
            method = PaymentTransactionMethod.ONLINE_GATEWAY,
            amount = 1000,
            currency = "IRR",
            idempotencyKey = null,
            status = PaymentStatus.INITIATED,
            gatewayProvider = null,
            gatewayAuthority = null,
            gatewayReferenceId = null,
            failureReason = null,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )

        assertThrows(DataIntegrityViolationException::class.java) {
            paymentTransactionRepository.save(transaction)
        }
    }
}
