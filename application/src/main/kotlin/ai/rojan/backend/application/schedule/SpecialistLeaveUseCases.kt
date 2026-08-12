package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SpecialistLeaveNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.LeaveId
import ai.rojan.backend.domain.schedule.SpecialistLeave
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDate

data class CreateSpecialistLeaveCommand(
    val specialistId: SpecialistId,
    val callerId: UserId,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String?,
)

class CreateSpecialistLeaveUseCase(
    private val specialistRepository: SpecialistRepository,
    private val leaveRepository: SpecialistLeaveRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: CreateSpecialistLeaveCommand): SpecialistLeave {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)

        val leave = SpecialistLeave.create(specialist.id, command.startDate, command.endDate, command.reason)
        return leaveRepository.save(leave)
    }
}

data class RemoveSpecialistLeaveCommand(
    val leaveId: LeaveId,
    val callerId: UserId,
)

class RemoveSpecialistLeaveUseCase(
    private val specialistRepository: SpecialistRepository,
    private val leaveRepository: SpecialistLeaveRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveSpecialistLeaveCommand) {
        val leave = leaveRepository.findById(command.leaveId)
            ?: throw SpecialistLeaveNotFoundException(command.leaveId.value.toString())
        val specialist = specialistRepository.findById(leave.specialistId)
            ?: throw SpecialistNotFoundException(leave.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)
        leaveRepository.deleteById(leave.id)
    }
}
