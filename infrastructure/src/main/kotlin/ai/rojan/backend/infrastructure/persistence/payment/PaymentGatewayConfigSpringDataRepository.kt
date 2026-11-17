package ai.rojan.backend.infrastructure.persistence.payment

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentGatewayConfigSpringDataRepository : JpaRepository<PaymentGatewayConfigJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): PaymentGatewayConfigJpaEntity?
}
