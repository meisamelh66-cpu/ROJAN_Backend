package ai.rojan.backend.application.schedule

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SpecialistBlockNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.BlockId
import ai.rojan.backend.domain.schedule.SpecialistBlock
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDate

data class CreateSpecialistBlockCommand(
    val specialistId: SpecialistId,
    val callerId: UserId,
    val date: LocalDate,
    val interval: TimeInterval,
    val reason: String?,
)

class CreateSpecialistBlockUseCase(
    private val specialistRepository: SpecialistRepository,
    private val blockRepository: SpecialistBlockRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: CreateSpecialistBlockCommand): SpecialistBlock {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)

        val block = SpecialistBlock.create(specialist.id, command.date, command.interval, command.reason)
        return blockRepository.save(block)
    }
}

data class RemoveSpecialistBlockCommand(
    val blockId: BlockId,
    val callerId: UserId,
)

class RemoveSpecialistBlockUseCase(
    private val specialistRepository: SpecialistRepository,
    private val blockRepository: SpecialistBlockRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveSpecialistBlockCommand) {
        val block = blockRepository.findById(command.blockId)
            ?: throw SpecialistBlockNotFoundException(command.blockId.value.toString())
        val specialist = specialistRepository.findById(block.specialistId)
            ?: throw SpecialistNotFoundException(block.specialistId.value.toString())
        salonPermissionResolver.requireCanManageSpecialist(specialist, command.callerId, Permission.MANAGE_SCHEDULE_ALL)
        blockRepository.deleteById(block.id)
    }
}
