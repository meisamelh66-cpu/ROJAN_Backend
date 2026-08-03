package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.LocalDate

interface SpecialistBlockRepository {
    fun save(block: SpecialistBlock): SpecialistBlock
    fun findById(id: BlockId): SpecialistBlock?
    fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistBlock>
    fun findBySpecialistIdAndDate(specialistId: SpecialistId, date: LocalDate): List<SpecialistBlock>
    fun deleteById(id: BlockId)
}
