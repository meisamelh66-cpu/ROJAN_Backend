package ai.rojan.backend.api.notification

import ai.rojan.backend.domain.notification.Notification
import java.time.Instant
import java.util.UUID

data class NotificationResponse(
    val id: UUID,
    val salonId: UUID,
    val userId: UUID?,
    val type: String,
    val title: String,
    val message: String,
    val severity: String,
    val category: String,
    val referenceId: String?,
    val referenceType: String?,
    val isRead: Boolean,
    val readAt: Instant?,
    val readBy: UUID?,
    val createdAt: Instant,
)

data class UnreadCountResponse(
    val salonId: UUID,
    val unreadCount: Long,
)

data class MarkAllReadResponse(
    val salonId: UUID,
    val markedCount: Int,
)

fun Notification.toResponse(): NotificationResponse = NotificationResponse(
    id = id.value,
    salonId = salonId.value,
    userId = userId?.value,
    type = type.name,
    title = title,
    message = message,
    severity = severity.name,
    category = category,
    referenceId = referenceId,
    referenceType = referenceType,
    isRead = isRead,
    readAt = readAt,
    readBy = readBy?.value,
    createdAt = createdAt,
)
