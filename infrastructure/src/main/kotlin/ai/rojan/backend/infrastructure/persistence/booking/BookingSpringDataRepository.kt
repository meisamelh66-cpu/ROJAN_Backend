package ai.rojan.backend.infrastructure.persistence.booking

import ai.rojan.backend.domain.booking.BookingStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface BookingSpringDataRepository : JpaRepository<BookingJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID, pageable: Pageable): Page<BookingJpaEntity>
    fun findBySalonIdAndStatus(salonId: UUID, status: BookingStatus, pageable: Pageable): Page<BookingJpaEntity>
    fun findByCustomerId(customerId: UUID, pageable: Pageable): Page<BookingJpaEntity>
    fun findByCustomerIdAndStatus(customerId: UUID, status: BookingStatus, pageable: Pageable): Page<BookingJpaEntity>
    fun findBySalonCustomerIdAndSalonId(salonCustomerId: UUID, salonId: UUID, pageable: Pageable): Page<BookingJpaEntity>
    fun findBySalonCustomerIdAndSalonIdAndStatus(salonCustomerId: UUID, salonId: UUID, status: BookingStatus, pageable: Pageable): Page<BookingJpaEntity>

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

    fun findBySalonIdAndStartTimeGreaterThanEqualAndStartTimeLessThan(
        salonId: UUID,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<BookingJpaEntity>

    @Query(
        """
        SELECT DISTINCT b.customerId FROM BookingJpaEntity b
        WHERE b.salonId = :salonId
        AND b.customerId IN :customerIds
        AND b.startTime < :before
        """,
    )
    fun findCustomerIdsWithBookingBefore(
        @Param("salonId") salonId: UUID,
        @Param("customerIds") customerIds: Collection<UUID>,
        @Param("before") before: LocalDateTime,
    ): List<UUID>

    fun findBySalonIdAndSalonCustomerIdInAndStatus(
        salonId: UUID,
        salonCustomerIds: Collection<UUID>,
        status: BookingStatus,
    ): List<BookingJpaEntity>

    // POST-MERGE-API-COMPATIBILITY-FIX-001: restored for the legacy SalonCustomerController.
    @Query("SELECT DISTINCT b.customerId FROM BookingJpaEntity b WHERE b.salonId = :salonId")
    fun findDistinctCustomerIdsBySalonId(@Param("salonId") salonId: UUID): List<UUID>
}
