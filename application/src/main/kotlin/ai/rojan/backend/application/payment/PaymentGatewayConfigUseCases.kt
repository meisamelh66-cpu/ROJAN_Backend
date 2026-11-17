package ai.rojan.backend.application.payment

import ai.rojan.backend.application.port.CredentialEncryptionPort
import ai.rojan.backend.application.port.GatewayTestResult
import ai.rojan.backend.application.port.PaymentGatewayPort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.PaymentGatewayConfigNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.payment.PaymentGatewayConfig
import ai.rojan.backend.domain.payment.PaymentGatewayConfigRepository
import ai.rojan.backend.domain.payment.PaymentGatewayProvider
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

/**
 * P1 Per-Salon Payment Gateway Configuration — every use case in this file enforces ownership the
 * same way [ai.rojan.backend.application.schedule.SetWorkingHoursUseCase] already does: look up the
 * real [ai.rojan.backend.domain.salon.Salon] by [SalonId] (never trust a caller-supplied id alone),
 * then [SalonPermissionResolver.require] [Permission.MANAGE_PAYMENT_GATEWAY] for the real caller —
 * never `request.salonId == callerId`-shaped ad-hoc checks. A caller without that permission at the
 * target salon always gets [ai.rojan.backend.domain.common.SalonAccessDeniedException] (mapped to
 * HTTP 403 by `GlobalExceptionHandler`), regardless of which salon they themselves own.
 */
private fun requireManageAccess(
    salonRepository: SalonRepository,
    salonPermissionResolver: SalonPermissionResolver,
    salonId: SalonId,
    callerId: UserId,
) {
    val salon = salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())
    salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_PAYMENT_GATEWAY)
}

data class SavePaymentGatewayConfigCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val provider: PaymentGatewayProvider,
    val merchantId: String,
)

/** Encrypts [SavePaymentGatewayConfigCommand.merchantId] ([CredentialEncryptionPort.encrypt]) before it ever reaches [PaymentGatewayConfigRepository] — no layer below this use case ever sees the plaintext. Replacing an existing salon's credential leaves [PaymentGatewayConfig.isActive] exactly as it was (see that class's own doc comment) — saving never silently (re)activates or deactivates. */
class SavePaymentGatewayConfigUseCase(
    private val salonRepository: SalonRepository,
    private val paymentGatewayConfigRepository: PaymentGatewayConfigRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val credentialEncryptionPort: CredentialEncryptionPort,
) {
    fun execute(command: SavePaymentGatewayConfigCommand): PaymentGatewayConfig {
        requireManageAccess(salonRepository, salonPermissionResolver, command.salonId, command.callerId)

        val encryptedMerchantId = credentialEncryptionPort.encrypt(command.merchantId)
        val maskedMerchantId = maskMerchantId(command.merchantId)
        val existing = paymentGatewayConfigRepository.findBySalonId(command.salonId)
        val config = if (existing != null) {
            existing.replaceCredential(encryptedMerchantId, maskedMerchantId)
            existing
        } else {
            PaymentGatewayConfig.create(command.salonId, command.provider, encryptedMerchantId, maskedMerchantId)
        }
        return paymentGatewayConfigRepository.save(config)
    }

    /** One-way display mask, computed from the real plaintext right here — the one place this use case genuinely holds it before encrypting. Never derived by decrypting [PaymentGatewayConfig.encryptedMerchantId] anywhere else. */
    private fun maskMerchantId(merchantId: String): String {
        val visibleCount = minOf(4, merchantId.length)
        val visible = merchantId.takeLast(visibleCount)
        return "•".repeat(merchantId.length - visibleCount) + visible
    }
}

data class GetPaymentGatewayConfigQuery(val salonId: SalonId, val callerId: UserId)

/** Returns `null` (never throws) when the salon has not saved a config yet — the "Empty / Not configured" UI state, not an error. The returned [PaymentGatewayConfig.encryptedMerchantId] is still ciphertext — the API layer's own response DTO is responsible for never surfacing it (see [PaymentGatewayConfigController]'s own doc comment), this use case does not re-shape it. */
class GetPaymentGatewayConfigUseCase(
    private val salonRepository: SalonRepository,
    private val paymentGatewayConfigRepository: PaymentGatewayConfigRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(query: GetPaymentGatewayConfigQuery): PaymentGatewayConfig? {
        requireManageAccess(salonRepository, salonPermissionResolver, query.salonId, query.callerId)
        return paymentGatewayConfigRepository.findBySalonId(query.salonId)
    }
}

data class ActivatePaymentGatewayConfigCommand(val salonId: SalonId, val callerId: UserId)

class ActivatePaymentGatewayConfigUseCase(
    private val salonRepository: SalonRepository,
    private val paymentGatewayConfigRepository: PaymentGatewayConfigRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: ActivatePaymentGatewayConfigCommand): PaymentGatewayConfig {
        requireManageAccess(salonRepository, salonPermissionResolver, command.salonId, command.callerId)
        val config = paymentGatewayConfigRepository.findBySalonId(command.salonId)
            ?: throw PaymentGatewayConfigNotFoundException(command.salonId.value.toString())
        config.activate()
        return paymentGatewayConfigRepository.save(config)
    }
}

data class DeactivatePaymentGatewayConfigCommand(val salonId: SalonId, val callerId: UserId)

class DeactivatePaymentGatewayConfigUseCase(
    private val salonRepository: SalonRepository,
    private val paymentGatewayConfigRepository: PaymentGatewayConfigRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: DeactivatePaymentGatewayConfigCommand): PaymentGatewayConfig {
        requireManageAccess(salonRepository, salonPermissionResolver, command.salonId, command.callerId)
        val config = paymentGatewayConfigRepository.findBySalonId(command.salonId)
            ?: throw PaymentGatewayConfigNotFoundException(command.salonId.value.toString())
        config.deactivate()
        return paymentGatewayConfigRepository.save(config)
    }
}

data class TestPaymentGatewayConfigCommand(val salonId: SalonId, val callerId: UserId)

/** Delegates to [PaymentGatewayPort.testCredential] — a real, minimal, non-persisting probe against the salon's *currently saved* credential (active or not; see that method's own doc comment). Never creates a [ai.rojan.backend.domain.payment.PaymentTransaction]. */
class TestPaymentGatewayConfigUseCase(
    private val salonRepository: SalonRepository,
    private val paymentGatewayConfigRepository: PaymentGatewayConfigRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val paymentGatewayPort: PaymentGatewayPort,
) {
    fun execute(command: TestPaymentGatewayConfigCommand): GatewayTestResult {
        requireManageAccess(salonRepository, salonPermissionResolver, command.salonId, command.callerId)
        paymentGatewayConfigRepository.findBySalonId(command.salonId)
            ?: throw PaymentGatewayConfigNotFoundException(command.salonId.value.toString())
        return paymentGatewayPort.testCredential(command.salonId)
    }
}
