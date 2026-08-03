package ai.rojan.backend.infrastructure.persistence.schedule

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface SpecialistLeaveSpringDataRepository : JpaRepository<SpecialistLeaveJpaEntity, UUID> {
    fun findBySpecialistId(specialistId: UUID): List<SpecialistLeaveJpaEntity>

    @Query(
        """
        SELECT l FROM SpecialistLeaveJpaEntity l
        WHERE l.specialistId = :specialistId
        AND l.startDate <= :date AND l.endDate >= :date
        """,
    )
    fun findBySpecialistIdCoveringDate(
        @Param("specialistId") specialistId: UUID,
        @Param("date") date: LocalDate,
    ): List<SpecialistLeaveJpaEntity>
}
