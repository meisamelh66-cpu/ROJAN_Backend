package ai.rojan.backend.application.notification

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.notification.Notification
import ai.rojan.backend.domain.notification.NotificationId
import ai.rojan.backend.domain.notification.NotificationRepository
import ai.rojan.backend.domain.notification.NotificationType
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant

internal class InMemoryNotificationRepository : NotificationRepository {
    private val store = mutableMapOf<NotificationId, Notification>()

    override fun save(notification: Notification): Notification =
        notification.also { store[it.id] = it }

    override fun findById(id: NotificationId): Notification? = store[id]

    override fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        unreadOnly: Boolean,
        category: String?,
    ): PageResult<Notification> {
        val filtered = store.values
            .filter { it.salonId == salonId }
            .filter { !unreadOnly || !it.isRead }
            .filter { category == null || it.category == category }
            .sortedByDescending { it.createdAt }

        val fromIndex = pageRequest.page * pageRequest.size
        val toIndex = (fromIndex + pageRequest.size).coerceAtMost(filtered.size)
        val content = if (fromIndex < filtered.size) filtered.subList(fromIndex, toIndex) else emptyList()

        return PageResult(
            content = content,
            page = pageRequest.page,
            size = pageRequest.size,
            totalElements = filtered.size.toLong(),
        )
    }

    override fun countUnreadBySalonId(salonId: SalonId): Long =
        store.values.count { it.salonId == salonId && !it.isRead }.toLong()

    override fun markAllReadBySalonId(salonId: SalonId, readBy: UserId, readAt: Instant): Int {
        var count = 0
        store.values
            .filter { it.salonId == salonId && !it.isRead }
            .forEach {
                store[it.id] = it.markAsRead(readBy, readAt)
                count++
            }
        return count
    }

    override fun existsByReference(
        salonId: SalonId,
        referenceType: String,
        referenceId: String,
        type: NotificationType,
    ): Boolean = store.values.any {
        it.salonId == salonId &&
            it.referenceType == referenceType &&
            it.referenceId == referenceId &&
            it.type == type
    }
}
