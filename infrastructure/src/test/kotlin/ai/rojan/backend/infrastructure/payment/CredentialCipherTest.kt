package ai.rojan.backend.infrastructure.payment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

/** P1 Per-Salon Payment Gateway Configuration — AES-256-GCM round-trip correctness for the real [CredentialCipher], independent of any use case/controller/repository. */
class CredentialCipherTest {

    private val validKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `decrypt reverses encrypt exactly, for a real-shaped merchant id`() {
        val cipher = CredentialCipher(PaymentCredentialProperties(validKey))

        val ciphertext = cipher.encrypt("a1b2c3d4-e5f6-7890-abcd-ef1234567890")

        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", cipher.decrypt(ciphertext))
    }

    @Test
    fun `encrypt never returns the plaintext itself`() {
        val cipher = CredentialCipher(PaymentCredentialProperties(validKey))

        val ciphertext = cipher.encrypt("a1b2c3d4-e5f6-7890-abcd-ef1234567890")

        assertNotEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", ciphertext)
    }

    @Test
    fun `encrypting the same plaintext twice produces different ciphertext - a fresh random IV every call`() {
        val cipher = CredentialCipher(PaymentCredentialProperties(validKey))

        val first = cipher.encrypt("same-merchant-id-0000000000000000000")
        val second = cipher.encrypt("same-merchant-id-0000000000000000000")

        assertNotEquals(first, second)
        assertEquals("same-merchant-id-0000000000000000000", cipher.decrypt(first))
        assertEquals("same-merchant-id-0000000000000000000", cipher.decrypt(second))
    }

    @Test
    fun `a key that does not decode to exactly 32 bytes fails loudly at construction, never silently`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))

        assertThrows(IllegalArgumentException::class.java) {
            CredentialCipher(PaymentCredentialProperties(shortKey))
        }
    }

    @Test
    fun `decrypting with a different key than the one that encrypted fails, never silently returns wrong plaintext`() {
        val cipherA = CredentialCipher(PaymentCredentialProperties(validKey))
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val cipherB = CredentialCipher(PaymentCredentialProperties(otherKey))

        val ciphertext = cipherA.encrypt("merchant-id-000000000000000000000000")

        assertThrows(Exception::class.java) {
            cipherB.decrypt(ciphertext)
        }
    }
}
