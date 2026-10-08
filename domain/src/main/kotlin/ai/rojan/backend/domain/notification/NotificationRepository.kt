package ai.rojan.backend.domain.notification

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant

interface NotificationRepository {
    fun save(notification: Notification): Notification
    fun findById(id: NotificationId): Notification?
    fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        unreadOnly: Boolean = false,
        category: String? = null,
    ): PageResult<Notification>
    fun countUnreadBySalonId(salonId: SalonId): Long
    fun markAllReadBySalonId(salonId: SalonId, readBy: UserId, readAt: Instant = Instant.now()): Int
    fun existsByReference(
        salonId: SalonId,
        referenceType: String,
        referenceId: String,
        type: NotificationType,
    ): Boolean
}
