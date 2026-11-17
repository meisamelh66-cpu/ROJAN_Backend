package ai.rojan.backend.application.port

/**
 * P1 Per-Salon Payment Gateway Configuration — output port for at-rest encryption of a salon's own
 * gateway credential (its real merchant id). [SavePaymentGatewayConfigUseCase] calls [encrypt] at
 * the one point a client-supplied plaintext credential first enters this backend; the real Zarinpal
 * adapter calls [decrypt] at the one point a plaintext credential is genuinely needed for an
 * outbound gateway call. No other layer ever sees plaintext. The real implementation (AES-256-GCM)
 * lives in infrastructure — this port exists so the Application layer, same as every other port in
 * this package, has zero dependency on infrastructure/crypto-library types.
 */
interface CredentialEncryptionPort {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}
