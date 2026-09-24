package ai.rojan.backend.infrastructure.persistence.device

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuthorizedDeviceSpringDataRepository : JpaRepository<AuthorizedDeviceJpaEntity, UUID> {
    fun findByUserIdAndSalonIdAndDeviceId(userId: UUID, salonId: UUID, deviceId: String): AuthorizedDeviceJpaEntity?
}
