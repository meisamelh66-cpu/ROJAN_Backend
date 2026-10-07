package ai.rojan.backend.domain.notification

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class NotificationId(val value: UUID) {
    companion object {
        fun generate(): NotificationId = NotificationId(UUID.randomUUID())
    }
}

enum class NotificationType {
    BOOKING_CREATED,
    BOOKING_CANCELLED,
}

enum class NotificationSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class Notification(
    val id: NotificationId,
    val salonId: SalonId,
    val userId: UserId?,
    val type: NotificationType,
    val title: String,
    val message: String,
    val severity: NotificationSeverity,
    val category: String,
    val referenceId: String?,
    val referenceType: String?,
    val isRead: Boolean,
    val readAt: Instant?,
    val readBy: UserId?,
    val createdAt: Instant,
) {
    fun markAsRead(by: UserId, at: Instant = Instant.now()): Notification {
        if (isRead) return this
        return copy(
            isRead = true,
            readAt = at,
            readBy = by,
        )
    }

    companion object {
        fun create(
            salonId: SalonId,
            userId: UserId? = null,
            type: NotificationType,
            title: String,
            message: String,
            severity: NotificationSeverity = NotificationSeverity.INFO,
            category: String = "bookings",
            referenceId: String? = null,
            referenceType: String? = null,
            createdAt: Instant = Instant.now(),
        ): Notification = Notification(
            id = NotificationId.generate(),
            salonId = salonId,
            userId = userId,
            type = type,
            title = title,
            message = message,
            severity = severity,
            category = category,
            referenceId = referenceId,
            referenceType = referenceType,
            isRead = false,
            readAt = null,
            readBy = null,
            createdAt = createdAt,
        )

        fun reconstitute(
            id: NotificationId,
            salonId: SalonId,
            userId: UserId?,
            type: NotificationType,
            title: String,
            message: String,
            severity: NotificationSeverity,
            category: String,
            referenceId: String?,
            referenceType: String?,
            isRead: Boolean,
            readAt: Instant?,
            readBy: UserId?,
            createdAt: Instant,
        ): Notification = Notification(
            id = id,
            salonId = salonId,
            userId = userId,
            type = type,
            title = title,
            message = message,
            severity = severity,
            category = category,
            referenceId = referenceId,
            referenceType = referenceType,
            isRead = isRead,
            readAt = readAt,
            readBy = readBy,
            createdAt = createdAt,
        )
    }
}
