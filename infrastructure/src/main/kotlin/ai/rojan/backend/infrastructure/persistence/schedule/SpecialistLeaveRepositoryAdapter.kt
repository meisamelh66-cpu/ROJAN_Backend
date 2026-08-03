package ai.rojan.backend.infrastructure.persistence.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.schedule.LeaveId
import ai.rojan.backend.domain.schedule.SpecialistLeave
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Repository
class SpecialistLeaveRepositoryAdapter(
    private val jpaRepository: SpecialistLeaveSpringDataRepository,
) : SpecialistLeaveRepository {

    override fun save(leave: SpecialistLeave): SpecialistLeave {
        val entity = jpaRepository.findById(leave.id.value).orElse(null)
            ?: SpecialistLeaveJpaEntity(
                id = leave.id.value,
                specialistId = leave.specialistId.value,
                startDate = leave.startDate,
                endDate = leave.endDate,
                reason = leave.reason,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: LeaveId): SpecialistLeave? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistLeave> =
        jpaRepository.findBySpecialistId(specialistId.value).map { it.toDomain() }

    override fun findBySpecialistIdCoveringDate(specialistId: SpecialistId, date: LocalDate): List<SpecialistLeave> =
        jpaRepository.findBySpecialistIdCoveringDate(specialistId.value, date).map { it.toDomain() }

    override fun deleteById(id: LeaveId) {
        jpaRepository.deleteById(id.value)
    }

    private fun SpecialistLeaveJpaEntity.toDomain(): SpecialistLeave = SpecialistLeave.reconstitute(
        id = LeaveId(id),
        specialistId = SpecialistId(specialistId),
        startDate = startDate,
        endDate = endDate,
        reason = reason,
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
