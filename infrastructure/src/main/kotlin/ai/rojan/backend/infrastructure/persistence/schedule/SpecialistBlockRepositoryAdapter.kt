package ai.rojan.backend.infrastructure.persistence.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.schedule.BlockId
import ai.rojan.backend.domain.schedule.SpecialistBlock
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Repository
class SpecialistBlockRepositoryAdapter(
    private val jpaRepository: SpecialistBlockSpringDataRepository,
) : SpecialistBlockRepository {

    override fun save(block: SpecialistBlock): SpecialistBlock {
        val entity = jpaRepository.findById(block.id.value).orElse(null)
            ?: SpecialistBlockJpaEntity(
                id = block.id.value,
                specialistId = block.specialistId.value,
                blockDate = block.date,
                startTime = block.interval.start,
                endTime = block.interval.end,
                reason = block.reason,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: BlockId): SpecialistBlock? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistBlock> =
        jpaRepository.findBySpecialistId(specialistId.value).map { it.toDomain() }

    override fun findBySpecialistIdAndDate(specialistId: SpecialistId, date: LocalDate): List<SpecialistBlock> =
        jpaRepository.findBySpecialistIdAndBlockDate(specialistId.value, date).map { it.toDomain() }

    override fun deleteById(id: BlockId) {
        jpaRepository.deleteById(id.value)
    }

    private fun SpecialistBlockJpaEntity.toDomain(): SpecialistBlock = SpecialistBlock.reconstitute(
        id = BlockId(id),
        specialistId = SpecialistId(specialistId),
        date = blockDate,
        interval = TimeInterval(startTime, endTime),
        reason = reason,
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
