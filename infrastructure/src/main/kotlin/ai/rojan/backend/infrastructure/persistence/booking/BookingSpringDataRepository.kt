package ai.rojan.backend.infrastructure.persistence.booking

import ai.rojan.backend.domain.booking.BookingStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface BookingSpringDataRepository : JpaRepository<BookingJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<BookingJpaEntity>
    fun findByCustomerId(customerId: UUID): List<BookingJpaEntity>

    @Query(
        """
        SELECT b FROM BookingJpaEntity b
        WHERE b.specialistId = :specialistId
        AND b.status IN :statuses
        AND b.startTime < :to AND b.endTime > :from
        """,
    )
    fun findActiveBySpecialistIdAndDateRange(
        @Param("specialistId") specialistId: UUID,
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
        @Param("statuses") statuses: List<BookingStatus>,
    ): List<BookingJpaEntity>

    @Query(
        """
        SELECT b FROM BookingJpaEntity b
        WHERE b.specialistId = :specialistId
        AND b.status IN :statuses
        AND b.startTime < :endTime AND b.endTime > :startTime
        AND (:excludeId IS NULL OR b.id <> :excludeId)
        """,
    )
    fun findOverlapping(
        @Param("specialistId") specialistId: UUID,
        @Param("startTime") startTime: LocalDateTime,
        @Param("endTime") endTime: LocalDateTime,
        @Param("excludeId") excludeId: UUID?,
        @Param("statuses") statuses: List<BookingStatus>,
    ): List<BookingJpaEntity>
}
