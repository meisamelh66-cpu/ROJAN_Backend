package ai.rojan.backend.infrastructure.persistence.notification

import ai.rojan.backend.domain.notification.NotificationType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface NotificationSpringDataRepository : JpaRepository<NotificationJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID, pageable: Pageable): Page<NotificationJpaEntity>

    fun findBySalonIdAndIsReadFalse(salonId: UUID, pageable: Pageable): Page<NotificationJpaEntity>

    fun findBySalonIdAndCategory(salonId: UUID, category: String, pageable: Pageable): Page<NotificationJpaEntity>

    fun findBySalonIdAndCategoryAndIsReadFalse(salonId: UUID, category: String, pageable: Pageable): Page<NotificationJpaEntity>

    fun countBySalonIdAndIsReadFalse(salonId: UUID): Long

    fun existsBySalonIdAndReferenceTypeAndReferenceIdAndType(
        salonId: UUID,
        referenceType: String,
        referenceId: String,
        type: NotificationType,
    ): Boolean

    @Modifying
    @Query("UPDATE NotificationJpaEntity n SET n.isRead = true, n.readBy = :readBy, n.readAt = :readAt WHERE n.salonId = :salonId AND n.isRead = false")
    fun markAllReadBySalonId(
        @Param("salonId") salonId: UUID,
        @Param("readBy") readBy: UUID,
        @Param("readAt") readAt: Instant,
    ): Int
}
