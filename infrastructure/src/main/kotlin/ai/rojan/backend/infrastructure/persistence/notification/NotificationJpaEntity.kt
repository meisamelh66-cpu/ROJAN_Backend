package ai.rojan.backend.infrastructure.persistence.notification

import ai.rojan.backend.domain.notification.NotificationSeverity
import ai.rojan.backend.domain.notification.NotificationType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notifications")
class NotificationJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Column(name = "user_id")
    val userId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    val type: NotificationType,

    @Column(name = "title", nullable = false, length = 200)
    val title: String,

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    val message: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    val severity: NotificationSeverity,

    @Column(name = "category", nullable = false, length = 50)
    val category: String,

    @Column(name = "reference_id", length = 100)
    val referenceId: String?,

    @Column(name = "reference_type", length = 50)
    val referenceType: String?,

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean,

    @Column(name = "read_at")
    var readAt: Instant?,

    @Column(name = "read_by")
    var readBy: UUID?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
