package ai.rojan.backend.infrastructure.persistence.schedule

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "specialist_blocks")
@EntityListeners(AuditingEntityListener::class)
class SpecialistBlockJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "specialist_id", nullable = false)
    var specialistId: UUID,

    @Column(name = "block_date", nullable = false)
    var blockDate: LocalDate,

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime,

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime,

    @Column(nullable = true)
    var reason: String?,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
