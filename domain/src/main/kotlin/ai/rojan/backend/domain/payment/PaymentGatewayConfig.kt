package ai.rojan.backend.domain.payment

import ai.rojan.backend.domain.salon.SalonId
import java.time.Instant

/**
 * P1 Per-Salon Payment Gateway Configuration: one salon's own credential for one [PaymentGatewayProvider]
 * — the real fix for the previous global-`ZARINPAL_MERCHANT_ID` design (every salon shared one
 * merchant id). [encryptedMerchantId] is opaque ciphertext to this aggregate and to every layer above
 * infrastructure — this class never decrypts it, never validates its plaintext shape, and never
 * exposes it outside [encryptedMerchantId] itself (no `toString()`/logging override is needed because
 * none is added — default data-free `toString` is avoided by not making this a `data class`, so a
 * stray `.toString()` call anywhere never accidentally prints the ciphertext either).
 *
 * [maskedMerchantId] is a separate, one-way, already-masked display string (e.g. `"••••••3456"`)
 * computed once from the real plaintext at the single moment it's genuinely in hand — the use case's
 * save path, *before* encrypting — never by decrypting [encryptedMerchantId] back. The API layer's
 * GET/PUT responses return only this field, never [encryptedMerchantId] itself.
 *
 * [isActive] is independent of whether a credential has ever been saved: saving/replacing a credential
 * ([replaceCredential]) never flips it — a Manager must explicitly [activate] a newly-saved credential
 * before real payments route through it, and [deactivate] stops routing without discarding the saved
 * credential.
 */
class PaymentGatewayConfig private constructor(
    val id: PaymentGatewayConfigId,
    val salonId: SalonId,
    val provider: PaymentGatewayProvider,
    encryptedMerchantId: String,
    maskedMerchantId: String,
    isActive: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var encryptedMerchantId: String = encryptedMerchantId
        private set

    var maskedMerchantId: String = maskedMerchantId
        private set

    var isActive: Boolean = isActive
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** Replaces the stored credential (and its display mask) — leaves [isActive] exactly as it was (see class doc). */
    fun replaceCredential(newEncryptedMerchantId: String, newMaskedMerchantId: String) {
        encryptedMerchantId = newEncryptedMerchantId
        maskedMerchantId = newMaskedMerchantId
        updatedAt = Instant.now()
    }

    fun activate() {
        isActive = true
        updatedAt = Instant.now()
    }

    fun deactivate() {
        isActive = false
        updatedAt = Instant.now()
    }

    companion object {
        /** A newly-saved config starts inactive — a Manager must [activate] it explicitly before it's used for a real payment. */
        fun create(salonId: SalonId, provider: PaymentGatewayProvider, encryptedMerchantId: String, maskedMerchantId: String): PaymentGatewayConfig {
            val now = Instant.now()
            return PaymentGatewayConfig(
                id = PaymentGatewayConfigId.new(),
                salonId = salonId,
                provider = provider,
                encryptedMerchantId = encryptedMerchantId,
                maskedMerchantId = maskedMerchantId,
                isActive = false,
                createdAt = now,
                updatedAt = now,
            )
        }

        /** Rehydrates a row already in the database — never applies [create]'s "starts inactive" default. */
        fun reconstitute(
            id: PaymentGatewayConfigId,
            salonId: SalonId,
            provider: PaymentGatewayProvider,
            encryptedMerchantId: String,
            maskedMerchantId: String,
            isActive: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): PaymentGatewayConfig = PaymentGatewayConfig(id, salonId, provider, encryptedMerchantId, maskedMerchantId, isActive, createdAt, updatedAt)
    }
}
