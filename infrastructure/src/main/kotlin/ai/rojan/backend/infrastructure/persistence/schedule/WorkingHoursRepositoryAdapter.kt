package ai.rojan.backend.infrastructure.persistence.schedule

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.schedule.WorkingHoursId
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant

@Repository
class WorkingHoursRepositoryAdapter(
    private val jpaRepository: WorkingHoursSpringDataRepository,
) : WorkingHoursRepository {

    override fun save(workingHours: WorkingHours): WorkingHours {
        val entity = jpaRepository.findById(workingHours.id.value).orElse(null)
            ?.apply {
                dayOfWeek = workingHours.dayOfWeek
                intervals = workingHours.intervals.toEmbeddables()
            }
            ?: WorkingHoursJpaEntity(
                id = workingHours.id.value,
                salonId = workingHours.salonId.value,
                dayOfWeek = workingHours.dayOfWeek,
                intervals = workingHours.intervals.toEmbeddables(),
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: WorkingHoursId): WorkingHours? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<WorkingHours> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findBySalonIdAndDayOfWeek(salonId: SalonId, dayOfWeek: DayOfWeek): WorkingHours? =
        jpaRepository.findBySalonIdAndDayOfWeek(salonId.value, dayOfWeek)?.toDomain()

    @Transactional
    override fun deleteBySalonIdAndDayOfWeek(salonId: SalonId, dayOfWeek: DayOfWeek) {
        jpaRepository.findBySalonIdAndDayOfWeek(salonId.value, dayOfWeek)?.let { jpaRepository.delete(it) }
    }

    private fun List<TimeInterval>.toEmbeddables(): MutableList<TimeIntervalEmbeddable> =
        map { TimeIntervalEmbeddable(it.start, it.end) }.toMutableList()

    private fun WorkingHoursJpaEntity.toDomain(): WorkingHours = WorkingHours.reconstitute(
        id = WorkingHoursId(id),
        salonId = SalonId(salonId),
        dayOfWeek = dayOfWeek,
        intervals = intervals.map { TimeInterval(it.startTime, it.endTime) },
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
