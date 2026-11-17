package ai.rojan.backend.bootstrap

import ai.rojan.backend.domain.payment.PaymentGatewayConfig
import ai.rojan.backend.domain.payment.PaymentGatewayConfigRepository
import ai.rojan.backend.domain.payment.PaymentGatewayProvider
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

/**
 * P1 Per-Salon Payment Gateway Configuration — real (embedded, no-Docker) PostgreSQL persistence
 * tests for [PaymentGatewayConfigRepository], mirroring [PaymentTransactionRepositoryAdapterTest]'s
 * own evidentiary bar: repository/JPA/migration layer only, no controller, no use case, no Zarinpal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PaymentGatewayConfigRepositoryAdapterTest {

    @Autowired
    private lateinit var paymentGatewayConfigRepository: PaymentGatewayConfigRepository

    @Autowired
    private lateinit var salonRepository: SalonRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private fun realOwner(): UserId {
        val user = User.register(
            email = Email("gateway-config.${System.nanoTime()}@example.com"),
            passwordHash = "hash",
            fullName = "Real Gateway Config Owner",
            role = UserRole.MANAGER,
        )
        return userRepository.save(user).id
    }

    private fun realSalon(): SalonId {
        val salon = Salon.create(
            ownerId = realOwner(),
            name = "Real Gateway Config Salon",
            description = null,
            phone = "09120000000",
            email = null,
            address = "Tehran",
        )
        return salonRepository.save(salon).id
    }

    @Test
    fun `a saved config round-trips through real Postgres with the exact encrypted value - this layer never decrypts`() {
        val salonId = realSalon()
        val saved = paymentGatewayConfigRepository.save(
            PaymentGatewayConfig.create(salonId, PaymentGatewayProvider.ZARINPAL, "ciphertext-looking-value-abc123", "••••d123"),
        )

        val found = paymentGatewayConfigRepository.findBySalonId(salonId)

        assertEquals(saved.id, found!!.id)
        assertEquals("ciphertext-looking-value-abc123", found.encryptedMerchantId)
        assertEquals("••••d123", found.maskedMerchantId)
        assertFalse(found.isActive)
    }

    @Test
    fun `findBySalonId returns null for a salon that never saved a config`() {
        val salonId = realSalon()

        assertNull(paymentGatewayConfigRepository.findBySalonId(salonId))
    }

    @Test
    fun `saving a second config for the same salon replaces the row in place rather than violating the DB unique constraint`() {
        val salonId = realSalon()
        val first = paymentGatewayConfigRepository.save(PaymentGatewayConfig.create(salonId, PaymentGatewayProvider.ZARINPAL, "first-ciphertext", "••••1111"))
        first.replaceCredential("second-ciphertext", "••••2222")

        val updated = paymentGatewayConfigRepository.save(first)

        assertEquals(first.id, updated.id)
        assertEquals("second-ciphertext", paymentGatewayConfigRepository.findBySalonId(salonId)!!.encryptedMerchantId)
    }

    @Test
    fun `the DB-level unique constraint on salon_id rejects a second, genuinely distinct config row for the same salon`() {
        val salonId = realSalon()
        paymentGatewayConfigRepository.save(PaymentGatewayConfig.create(salonId, PaymentGatewayProvider.ZARINPAL, "first-ciphertext", "••••1111"))
        val distinctConfig = PaymentGatewayConfig.create(salonId, PaymentGatewayProvider.ZARINPAL, "second-ciphertext", "••••2222")

        assertThrows(DataIntegrityViolationException::class.java) {
            paymentGatewayConfigRepository.save(distinctConfig)
        }
    }

    @Test
    fun `activate then save persists isActive true, then deactivate then save persists it back to false`() {
        val salonId = realSalon()
        val config = paymentGatewayConfigRepository.save(PaymentGatewayConfig.create(salonId, PaymentGatewayProvider.ZARINPAL, "ciphertext", "••••3333"))

        config.activate()
        paymentGatewayConfigRepository.save(config)
        assertTrue(paymentGatewayConfigRepository.findBySalonId(salonId)!!.isActive)

        config.deactivate()
        paymentGatewayConfigRepository.save(config)
        assertFalse(paymentGatewayConfigRepository.findBySalonId(salonId)!!.isActive)
    }

    @Test
    fun `two different salons each have their own independent config row - never share or overwrite each other's`() {
        val salonA = realSalon()
        val salonB = realSalon()
        paymentGatewayConfigRepository.save(PaymentGatewayConfig.create(salonA, PaymentGatewayProvider.ZARINPAL, "salon-a-ciphertext", "••••aaaa"))
        paymentGatewayConfigRepository.save(PaymentGatewayConfig.create(salonB, PaymentGatewayProvider.ZARINPAL, "salon-b-ciphertext", "••••bbbb"))

        val foundA = paymentGatewayConfigRepository.findBySalonId(salonA)!!
        val foundB = paymentGatewayConfigRepository.findBySalonId(salonB)!!

        assertEquals("salon-a-ciphertext", foundA.encryptedMerchantId)
        assertEquals("salon-b-ciphertext", foundB.encryptedMerchantId)
        assertNotEquals(foundA.id, foundB.id)
    }
}
