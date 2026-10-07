package ai.rojan.backend.infrastructure.persistence.notification

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.notification.Notification
import ai.rojan.backend.domain.notification.NotificationId
import ai.rojan.backend.domain.notification.NotificationRepository
import ai.rojan.backend.domain.notification.NotificationType
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class NotificationRepositoryAdapter(
    private val jpaRepository: NotificationSpringDataRepository,
) : NotificationRepository {

    override fun save(notification: Notification): Notification {
        val entity = NotificationJpaEntity(
            id = notification.id.value,
            salonId = notification.salonId.value,
            userId = notification.userId?.value,
            type = notification.type,
            title = notification.title,
            message = notification.message,
            severity = notification.severity,
            category = notification.category,
            referenceId = notification.referenceId,
            referenceType = notification.referenceType,
            isRead = notification.isRead,
            readAt = notification.readAt,
            readBy = notification.readBy?.value,
            createdAt = notification.createdAt,
        )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: NotificationId): Notification? =
        jpaRepository.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        unreadOnly: Boolean,
        category: String?,
    ): PageResult<Notification> {
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val page = when {
            category != null && unreadOnly -> jpaRepository.findBySalonIdAndCategoryAndIsReadFalse(salonId.value, category, pageable)
            category != null -> jpaRepository.findBySalonIdAndCategory(salonId.value, category, pageable)
            unreadOnly -> jpaRepository.findBySalonIdAndIsReadFalse(salonId.value, pageable)
            else -> jpaRepository.findBySalonId(salonId.value, pageable)
        }
        return PageResult(
            content = page.content.map { it.toDomain() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
        )
    }

    override fun countUnreadBySalonId(salonId: SalonId): Long =
        jpaRepository.countBySalonIdAndIsReadFalse(salonId.value)

    @Transactional
    override fun markAllReadBySalonId(salonId: SalonId, readBy: UserId, readAt: Instant): Int =
        jpaRepository.markAllReadBySalonId(salonId.value, readBy.value, readAt)

    override fun existsByReference(
        salonId: SalonId,
        referenceType: String,
        referenceId: String,
        type: NotificationType,
    ): Boolean = jpaRepository.existsBySalonIdAndReferenceTypeAndReferenceIdAndType(
        salonId.value,
        referenceType,
        referenceId,
        type,
    )

    private fun NotificationJpaEntity.toDomain(): Notification = Notification.reconstitute(
        id = NotificationId(id),
        salonId = SalonId(salonId),
        userId = userId?.let { UserId(it) },
        type = type,
        title = title,
        message = message,
        severity = severity,
        category = category,
        referenceId = referenceId,
        referenceType = referenceType,
        isRead = isRead,
        readAt = readAt,
        readBy = readBy?.let { UserId(it) },
        createdAt = createdAt,
    )
}
