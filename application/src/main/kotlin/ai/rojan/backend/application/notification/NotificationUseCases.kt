package ai.rojan.backend.application.notification

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.NotificationNotFoundException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.notification.Notification
import ai.rojan.backend.domain.notification.NotificationId
import ai.rojan.backend.domain.notification.NotificationRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

class GetSalonNotificationsUseCase(
    private val salonRepository: SalonRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val notificationRepository: NotificationRepository,
) {
    fun execute(
        salonId: SalonId,
        callerId: UserId,
        pageRequest: PageRequest,
        unreadOnly: Boolean = false,
        category: String? = null,
    ): PageResult<Notification> {
        val salon = salonRepository.findById(salonId)?.takeIf { it.active }
            ?: throw SalonNotFoundException(salonId.value.toString())
        salon.requireActivated()
        salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_BOOKINGS)
        return notificationRepository.findBySalonId(salon.id, pageRequest, unreadOnly, category)
    }
}

class GetSalonUnreadNotificationCountUseCase(
    private val salonRepository: SalonRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val notificationRepository: NotificationRepository,
) {
    fun execute(salonId: SalonId, callerId: UserId): Long {
        val salon = salonRepository.findById(salonId)?.takeIf { it.active }
            ?: throw SalonNotFoundException(salonId.value.toString())
        salon.requireActivated()
        salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_BOOKINGS)
        return notificationRepository.countUnreadBySalonId(salon.id)
    }
}

class MarkNotificationReadUseCase(
    private val salonRepository: SalonRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val notificationRepository: NotificationRepository,
) {
    fun execute(salonId: SalonId, notificationId: NotificationId, callerId: UserId): Notification {
        val salon = salonRepository.findById(salonId)?.takeIf { it.active }
            ?: throw SalonNotFoundException(salonId.value.toString())
        salon.requireActivated()
        salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_BOOKINGS)

        val notification = notificationRepository.findById(notificationId)
            ?: throw NotificationNotFoundException(notificationId.value.toString())
        if (notification.salonId != salon.id) {
            throw NotificationNotFoundException(notificationId.value.toString())
        }

        val updated = notification.markAsRead(callerId)
        return notificationRepository.save(updated)
    }
}

class MarkAllNotificationsReadUseCase(
    private val salonRepository: SalonRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val notificationRepository: NotificationRepository,
) {
    fun execute(salonId: SalonId, callerId: UserId): Int {
        val salon = salonRepository.findById(salonId)?.takeIf { it.active }
            ?: throw SalonNotFoundException(salonId.value.toString())
        salon.requireActivated()
        salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_BOOKINGS)
        return notificationRepository.markAllReadBySalonId(salon.id, callerId)
    }
}
