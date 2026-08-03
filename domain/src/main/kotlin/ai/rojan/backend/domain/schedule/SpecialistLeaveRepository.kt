package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.LocalDate

interface SpecialistLeaveRepository {
    fun save(leave: SpecialistLeave): SpecialistLeave
    fun findById(id: LeaveId): SpecialistLeave?
    fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistLeave>
    fun findBySpecialistIdCoveringDate(specialistId: SpecialistId, date: LocalDate): List<SpecialistLeave>
    fun deleteById(id: LeaveId)
}
