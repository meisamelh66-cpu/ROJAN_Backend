package ai.rojan.backend.infrastructure.persistence.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.schedule.ScheduleOverrideId
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverride
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Repository
class SpecialistScheduleOverrideRepositoryAdapter(
    private val jpaRepository: SpecialistScheduleOverrideSpringDataRepository,
) : SpecialistScheduleOverrideRepository {

    override fun save(override: SpecialistScheduleOverride): SpecialistScheduleOverride {
        val entity = jpaRepository.findById(override.id.value).orElse(null)
            ?.apply {
                intervals = override.intervals.toEmbeddables()
                reason = override.reason
            }
            ?: SpecialistScheduleOverrideJpaEntity(
                id = override.id.value,
                specialistId = override.specialistId.value,
                overrideDate = override.date,
                intervals = override.intervals.toEmbeddables(),
                reason = override.reason,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: ScheduleOverrideId): SpecialistScheduleOverride? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistScheduleOverride> =
        jpaRepository.findBySpecialistId(specialistId.value).map { it.toDomain() }

    override fun findBySpecialistIdAndDate(specialistId: SpecialistId, date: LocalDate): SpecialistScheduleOverride? =
        jpaRepository.findBySpecialistIdAndOverrideDate(specialistId.value, date)?.toDomain()

    override fun deleteById(id: ScheduleOverrideId) {
        jpaRepository.deleteById(id.value)
    }

    private fun List<TimeInterval>.toEmbeddables(): MutableList<TimeIntervalEmbeddable> =
        map { TimeIntervalEmbeddable(it.start, it.end) }.toMutableList()

    private fun SpecialistScheduleOverrideJpaEntity.toDomain(): SpecialistScheduleOverride =
        SpecialistScheduleOverride.reconstitute(
            id = ScheduleOverrideId(id),
            specialistId = SpecialistId(specialistId),
            date = overrideDate,
            intervals = intervals.map { TimeInterval(it.startTime, it.endTime) },
            reason = reason,
            createdAt = createdAt ?: Instant.EPOCH,
            updatedAt = updatedAt ?: Instant.EPOCH,
        )
}
