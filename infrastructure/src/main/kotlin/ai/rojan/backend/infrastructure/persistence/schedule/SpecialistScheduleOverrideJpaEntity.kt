package ai.rojan.backend.infrastructure.persistence.schedule

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "specialist_schedule_overrides")
@EntityListeners(AuditingEntityListener::class)
class SpecialistScheduleOverrideJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "specialist_id", nullable = false)
    var specialistId: UUID,

    @Column(name = "override_date", nullable = false)
    var overrideDate: LocalDate,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "specialist_schedule_override_intervals",
        joinColumns = [JoinColumn(name = "override_id")],
    )
    @OrderColumn(name = "interval_order")
    var intervals: MutableList<TimeIntervalEmbeddable>,

    @Column(nullable = true)
    var reason: String?,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
