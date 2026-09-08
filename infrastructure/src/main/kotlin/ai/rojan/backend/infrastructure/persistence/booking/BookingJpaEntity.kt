package ai.rojan.backend.infrastructure.persistence.booking

import ai.rojan.backend.domain.booking.BookingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "bookings")
@EntityListeners(AuditingEntityListener::class)
class BookingJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    var salonId: UUID,

    @Column(name = "service_id", nullable = false)
    var serviceId: UUID,

    @Column(name = "specialist_id", nullable = false)
    var specialistId: UUID,

    @Column(name = "customer_id", nullable = false)
    var customerId: UUID,

    // BACKEND-CRM-CUSTOMER-IDENTITY-001: nullable link to the salon's CRM
    // record. Null only for bookings created before V7 / not yet backfilled.
    @Column(name = "salon_customer_id", nullable = true)
    var salonCustomerId: UUID?,

    @Column(name = "start_time", nullable = false)
    var startTime: LocalDateTime,

    @Column(name = "end_time", nullable = false)
    var endTime: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: BookingStatus,

    @Column(nullable = true)
    var notes: String?,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
