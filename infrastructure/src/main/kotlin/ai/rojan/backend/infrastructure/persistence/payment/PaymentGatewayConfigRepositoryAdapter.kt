package ai.rojan.backend.infrastructure.persistence.payment

import ai.rojan.backend.domain.payment.PaymentGatewayConfig
import ai.rojan.backend.domain.payment.PaymentGatewayConfigId
import ai.rojan.backend.domain.payment.PaymentGatewayConfigRepository
import ai.rojan.backend.domain.salon.SalonId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class PaymentGatewayConfigRepositoryAdapter(
    private val jpaRepository: PaymentGatewayConfigSpringDataRepository,
) : PaymentGatewayConfigRepository {

    override fun findBySalonId(salonId: SalonId): PaymentGatewayConfig? =
        jpaRepository.findBySalonId(salonId.value)?.toDomain()

    @Transactional
    override fun save(config: PaymentGatewayConfig): PaymentGatewayConfig {
        val entity = jpaRepository.findById(config.id.value).orElse(null)
            ?.applyMutableFieldsFrom(config)
            ?: config.toNewEntity()
        return jpaRepository.saveAndFlush(entity).toDomain()
    }

    private fun PaymentGatewayConfigJpaEntity.applyMutableFieldsFrom(config: PaymentGatewayConfig): PaymentGatewayConfigJpaEntity = apply {
        encryptedMerchantId = config.encryptedMerchantId
        maskedMerchantId = config.maskedMerchantId
        isActive = config.isActive
    }

    private fun PaymentGatewayConfig.toNewEntity() = PaymentGatewayConfigJpaEntity(
        id = id.value,
        salonId = salonId.value,
        provider = provider,
        encryptedMerchantId = encryptedMerchantId,
        maskedMerchantId = maskedMerchantId,
        isActive = isActive,
    )

    private fun PaymentGatewayConfigJpaEntity.toDomain(): PaymentGatewayConfig = PaymentGatewayConfig.reconstitute(
        id = PaymentGatewayConfigId(id),
        salonId = SalonId(salonId),
        provider = provider,
        encryptedMerchantId = encryptedMerchantId,
        maskedMerchantId = maskedMerchantId,
        isActive = isActive,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
