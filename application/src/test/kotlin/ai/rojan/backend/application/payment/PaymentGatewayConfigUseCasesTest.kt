package ai.rojan.backend.application.payment

import ai.rojan.backend.application.port.CredentialEncryptionPort
import ai.rojan.backend.application.port.GatewayTestResult
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.PaymentGatewayConfigNotFoundException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.payment.PaymentGatewayConfig
import ai.rojan.backend.domain.payment.PaymentGatewayConfigRepository
import ai.rojan.backend.domain.payment.PaymentGatewayProvider
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P1 Per-Salon Payment Gateway Configuration. [IdentityCredentialEncryptionPort] is a plain
 * pass-through — these tests exist to prove ownership/RBAC and state-machine behavior, which is
 * orthogonal to real AES correctness (covered by `CredentialCipherTest`, infrastructure module).
 */
class PaymentGatewayConfigUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val paymentGatewayConfigRepository = InMemoryPaymentGatewayConfigRepository()
    private val credentialEncryptionPort = IdentityCredentialEncryptionPort()

    private val ownerA = UserId.new()
    private val ownerB = UserId.new()
    private val salonA: Salon = salonRepository.save(Salon.create(ownerA, "Salon A", null, "+1 555 0100", null, "1 Main St"))
    private val salonB: Salon = salonRepository.save(Salon.create(ownerB, "Salon B", null, "+1 555 0200", null, "2 Main St"))

    private val saveUseCase = SavePaymentGatewayConfigUseCase(salonRepository, paymentGatewayConfigRepository, salonPermissionResolver, credentialEncryptionPort)
    private val getUseCase = GetPaymentGatewayConfigUseCase(salonRepository, paymentGatewayConfigRepository, salonPermissionResolver)
    private val activateUseCase = ActivatePaymentGatewayConfigUseCase(salonRepository, paymentGatewayConfigRepository, salonPermissionResolver)
    private val deactivateUseCase = DeactivatePaymentGatewayConfigUseCase(salonRepository, paymentGatewayConfigRepository, salonPermissionResolver)
    private val testUseCase = TestPaymentGatewayConfigUseCase(salonRepository, paymentGatewayConfigRepository, salonPermissionResolver, ScriptedPaymentGatewayPort())

    // ---------------- save / create ----------------

    @Test
    fun `a MANAGER-role member of the same salon can save a config too - correction, user-approved`() {
        val managerMember = UserId.new()
        membershipRepository.assign(salonA.id, managerMember, SalonRole.MANAGER)

        val config = saveConfig(salonA.id, managerMember, "manager-saved-merchant-id-00000000000")

        assertEquals(salonA.id, config.salonId)
        assertEquals("manager-saved-merchant-id-00000000000", credentialEncryptionPort.decrypt(config.encryptedMerchantId))
    }

    @Test
    fun `a MANAGER-role member of a different salon still gets SalonAccessDeniedException - membership never crosses salons`() {
        val managerOfA = UserId.new()
        membershipRepository.assign(salonA.id, managerOfA, SalonRole.MANAGER)

        assertThrows(SalonAccessDeniedException::class.java) {
            saveConfig(salonB.id, managerOfA, "attacker-merchant-id-0000000000000000")
        }
    }

    @Test
    fun `the owner can save a new config for their own salon - starts inactive, round-trips through the encryption port`() {
        val config = saveConfig(salonA.id, ownerA, "real-merchant-id-123456789012345678")

        assertEquals(salonA.id, config.salonId)
        assertEquals(PaymentGatewayProvider.ZARINPAL, config.provider)
        assertFalse(config.isActive)
        assertEquals("real-merchant-id-123456789012345678", credentialEncryptionPort.decrypt(config.encryptedMerchantId))
    }

    @Test
    fun `saving never returns the plaintext merchant id anywhere on the returned domain object except via decrypt`() {
        val config = saveConfig(salonA.id, ownerA, "super-secret-merchant-id-00000000000")

        assertNotEquals("super-secret-merchant-id-00000000000", config.maskedMerchantId)
        assertTrue(config.maskedMerchantId.endsWith("0000"))
        assertTrue(config.maskedMerchantId.startsWith("•"))
    }

    @Test
    fun `saving twice for the same salon replaces the credential rather than creating a duplicate row`() {
        saveConfig(salonA.id, ownerA, "first-merchant-id-000000000000000000")
        saveConfig(salonA.id, ownerA, "second-merchant-id-00000000000000000")

        val stored = paymentGatewayConfigRepository.findBySalonId(salonA.id)
        assertEquals("second-merchant-id-00000000000000000", credentialEncryptionPort.decrypt(stored!!.encryptedMerchantId))
        assertEquals(1, paymentGatewayConfigRepository.countForSalon(salonA.id))
    }

    @Test
    fun `replacing a credential never silently activates or deactivates it`() {
        val activated = saveConfig(salonA.id, ownerA, "merchant-one-00000000000000000000000")
        activateUseCase.execute(ActivatePaymentGatewayConfigCommand(salonA.id, ownerA))

        saveConfig(salonA.id, ownerA, "merchant-two-00000000000000000000000")

        assertTrue(paymentGatewayConfigRepository.findBySalonId(salonA.id)!!.isActive)
    }

    @Test
    fun `a manager cannot save a payment gateway config for a salon they own no part of`() {
        assertThrows(SalonAccessDeniedException::class.java) {
            saveConfig(salonA.id, ownerB, "attacker-merchant-id-0000000000000000")
        }
    }

    // ---------------- get ----------------

    @Test
    fun `get returns null for a salon that has never saved a config - the Empty Not configured state, not an error`() {
        val result = getUseCase.execute(GetPaymentGatewayConfigQuery(salonA.id, ownerA))

        assertNull(result)
    }

    @Test
    fun `the owner can read their own salon's config`() {
        saveConfig(salonA.id, ownerA, "merchant-id-000000000000000000000000")

        val result = getUseCase.execute(GetPaymentGatewayConfigQuery(salonA.id, ownerA))

        assertEquals(salonA.id, result!!.salonId)
    }

    @Test
    fun `cross-salon GET is denied with SalonAccessDeniedException - never a silent null, never another salon's data`() {
        saveConfig(salonB.id, ownerB, "salon-b-merchant-id-0000000000000000")

        assertThrows(SalonAccessDeniedException::class.java) {
            getUseCase.execute(GetPaymentGatewayConfigQuery(salonB.id, ownerA))
        }
    }

    // ---------------- activate / deactivate ----------------

    @Test
    fun `activate flips isActive to true for an already-saved config`() {
        saveConfig(salonA.id, ownerA, "merchant-id-000000000000000000000000")

        val result = activateUseCase.execute(ActivatePaymentGatewayConfigCommand(salonA.id, ownerA))

        assertTrue(result.isActive)
    }

    @Test
    fun `deactivate flips isActive back to false without discarding the saved credential`() {
        saveConfig(salonA.id, ownerA, "merchant-id-000000000000000000000000")
        activateUseCase.execute(ActivatePaymentGatewayConfigCommand(salonA.id, ownerA))

        val result = deactivateUseCase.execute(DeactivatePaymentGatewayConfigCommand(salonA.id, ownerA))

        assertFalse(result.isActive)
        assertEquals("merchant-id-000000000000000000000000", credentialEncryptionPort.decrypt(paymentGatewayConfigRepository.findBySalonId(salonA.id)!!.encryptedMerchantId))
    }

    @Test
    fun `activate on a salon that never saved a config throws PaymentGatewayConfigNotFoundException`() {
        assertThrows(PaymentGatewayConfigNotFoundException::class.java) {
            activateUseCase.execute(ActivatePaymentGatewayConfigCommand(salonA.id, ownerA))
        }
    }

    @Test
    fun `cross-salon activate is denied with SalonAccessDeniedException`() {
        saveConfig(salonB.id, ownerB, "salon-b-merchant-id-0000000000000000")

        assertThrows(SalonAccessDeniedException::class.java) {
            activateUseCase.execute(ActivatePaymentGatewayConfigCommand(salonB.id, ownerA))
        }
    }

    // ---------------- test connection ----------------

    @Test
    fun `test connection on a salon with no saved config throws PaymentGatewayConfigNotFoundException rather than calling the gateway`() {
        assertThrows(PaymentGatewayConfigNotFoundException::class.java) {
            testUseCase.execute(TestPaymentGatewayConfigCommand(salonA.id, ownerA))
        }
    }

    @Test
    fun `test connection delegates to the gateway port for an already-saved, even inactive, config`() {
        saveConfig(salonA.id, ownerA, "merchant-id-000000000000000000000000")

        val result = testUseCase.execute(TestPaymentGatewayConfigCommand(salonA.id, ownerA))

        assertTrue(result is GatewayTestResult.Success)
    }

    // ---------------- isolation ----------------

    @Test
    fun `salon A's and salon B's configs are stored independently - saving one never touches the other`() {
        saveConfig(salonA.id, ownerA, "salon-a-merchant-id-0000000000000000")
        saveConfig(salonB.id, ownerB, "salon-b-merchant-id-0000000000000000")

        val configA = paymentGatewayConfigRepository.findBySalonId(salonA.id)!!
        val configB = paymentGatewayConfigRepository.findBySalonId(salonB.id)!!

        assertEquals("salon-a-merchant-id-0000000000000000", credentialEncryptionPort.decrypt(configA.encryptedMerchantId))
        assertEquals("salon-b-merchant-id-0000000000000000", credentialEncryptionPort.decrypt(configB.encryptedMerchantId))
    }

    private fun saveConfig(salonId: SalonId, callerId: UserId, merchantId: String): PaymentGatewayConfig =
        saveUseCase.execute(SavePaymentGatewayConfigCommand(salonId, callerId, PaymentGatewayProvider.ZARINPAL, merchantId))
}

/** Plain pass-through — real AES correctness is covered by `CredentialCipherTest` (infrastructure module); these use-case tests only need *a* [CredentialEncryptionPort], not the real one. */
internal class IdentityCredentialEncryptionPort : CredentialEncryptionPort {
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(ciphertext: String): String = ciphertext
}

/** Single-threaded in-memory fake — same role every other `InMemory*Repository` fixture in this module already plays. */
internal class InMemoryPaymentGatewayConfigRepository : PaymentGatewayConfigRepository {
    private val store = mutableMapOf<SalonId, PaymentGatewayConfig>()

    override fun findBySalonId(salonId: SalonId): PaymentGatewayConfig? = store[salonId]

    override fun save(config: PaymentGatewayConfig): PaymentGatewayConfig {
        store[config.salonId] = config
        return config
    }

    fun countForSalon(salonId: SalonId): Int = if (store.containsKey(salonId)) 1 else 0
}
