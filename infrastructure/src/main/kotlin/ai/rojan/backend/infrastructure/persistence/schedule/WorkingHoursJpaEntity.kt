package ai.rojan.backend.infrastructure.persistence.schedule

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "working_hours")
@EntityListeners(AuditingEntityListener::class)
class WorkingHoursJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    var salonId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    var dayOfWeek: DayOfWeek,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "working_hours_intervals", joinColumns = [JoinColumn(name = "working_hours_id")])
    @OrderColumn(name = "interval_order")
    var intervals: MutableList<TimeIntervalEmbeddable>,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
