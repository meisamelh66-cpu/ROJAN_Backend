package ai.rojan.backend.infrastructure.persistence.salon

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "specialist_services")
class SpecialistServiceJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "specialist_id", nullable = false)
    val specialistId: UUID,

    @Column(name = "service_id", nullable = false)
    val serviceId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
