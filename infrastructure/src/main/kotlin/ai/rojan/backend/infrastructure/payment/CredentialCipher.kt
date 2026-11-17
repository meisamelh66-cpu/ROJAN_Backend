package ai.rojan.backend.infrastructure.payment

import ai.rojan.backend.application.port.CredentialEncryptionPort
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * P1 Per-Salon Payment Gateway Configuration: AES-256-GCM at-rest encryption for
 * [ai.rojan.backend.domain.payment.PaymentGatewayConfig.encryptedMerchantId] - the minimal,
 * production-safe encryption this codebase had no existing utility for (confirmed by audit: no
 * `Cipher`/AES/encryption-at-rest code anywhere else in this repository). Used ONLY at the two
 * points a real merchant id plaintext is actually needed: [PaymentGatewayConfigUseCases]' save path
 * ([encrypt]) and [ZarinpalPaymentGatewayAdapter]'s real gateway call ([decrypt]) - every other
 * layer (domain, the config repository, the API response DTOs) only ever sees the opaque
 * ciphertext string this class produces.
 *
 * A fresh random IV is generated per [encrypt] call (never reused - GCM's one hard security
 * requirement) and stored alongside the ciphertext+tag in the single returned base64 string
 * (`iv || ciphertext+tag`), so [decrypt] needs nothing but that one string and the configured key.
 */
@Component
class CredentialCipher(
    properties: PaymentCredentialProperties,
) : CredentialEncryptionPort {
    private val secretKey: SecretKeySpec = run {
        val keyBytes = Base64.getDecoder().decode(properties.credentialEncryptionKey)
        require(keyBytes.size == KEY_LENGTH_BYTES) {
            "rojan.payment.credential-encryption-key must decode to exactly $KEY_LENGTH_BYTES bytes (AES-256), was ${keyBytes.size}"
        }
        SecretKeySpec(keyBytes, "AES")
    }

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    override fun decrypt(ciphertext: String): String {
        val encoded = ciphertext
        val combined = Base64.getDecoder().decode(encoded)
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_LENGTH_BYTES = 32
        const val IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
