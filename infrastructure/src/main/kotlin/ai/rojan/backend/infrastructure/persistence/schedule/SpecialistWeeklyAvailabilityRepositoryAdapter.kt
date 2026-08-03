package ai.rojan.backend.infrastructure.persistence.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WeeklyAvailabilityId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant

@Repository
class SpecialistWeeklyAvailabilityRepositoryAdapter(
    private val jpaRepository: SpecialistWeeklyAvailabilitySpringDataRepository,
) : SpecialistWeeklyAvailabilityRepository {

    override fun save(availability: SpecialistWeeklyAvailability): SpecialistWeeklyAvailability {
        val entity = jpaRepository.findById(availability.id.value).orElse(null)
            ?.apply {
                dayOfWeek = availability.dayOfWeek
                intervals = availability.intervals.toEmbeddables()
            }
            ?: SpecialistWeeklyAvailabilityJpaEntity(
                id = availability.id.value,
                specialistId = availability.specialistId.value,
                dayOfWeek = availability.dayOfWeek,
                intervals = availability.intervals.toEmbeddables(),
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: WeeklyAvailabilityId): SpecialistWeeklyAvailability? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistWeeklyAvailability> =
        jpaRepository.findBySpecialistId(specialistId.value).map { it.toDomain() }

    override fun findBySpecialistIdAndDayOfWeek(specialistId: SpecialistId, dayOfWeek: DayOfWeek): SpecialistWeeklyAvailability? =
        jpaRepository.findBySpecialistIdAndDayOfWeek(specialistId.value, dayOfWeek)?.toDomain()

    @Transactional
    override fun deleteBySpecialistIdAndDayOfWeek(specialistId: SpecialistId, dayOfWeek: DayOfWeek) {
        jpaRepository.findBySpecialistIdAndDayOfWeek(specialistId.value, dayOfWeek)?.let { jpaRepository.delete(it) }
    }

    private fun List<TimeInterval>.toEmbeddables(): MutableList<TimeIntervalEmbeddable> =
        map { TimeIntervalEmbeddable(it.start, it.end) }.toMutableList()

    private fun SpecialistWeeklyAvailabilityJpaEntity.toDomain(): SpecialistWeeklyAvailability =
        SpecialistWeeklyAvailability.reconstitute(
            id = WeeklyAvailabilityId(id),
            specialistId = SpecialistId(specialistId),
            dayOfWeek = dayOfWeek,
            intervals = intervals.map { TimeInterval(it.startTime, it.endTime) },
            createdAt = createdAt ?: Instant.EPOCH,
            updatedAt = updatedAt ?: Instant.EPOCH,
        )
}
