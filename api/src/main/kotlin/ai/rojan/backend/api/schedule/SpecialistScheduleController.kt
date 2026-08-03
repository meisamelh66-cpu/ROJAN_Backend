package ai.rojan.backend.api.schedule

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.schedule.CreateSpecialistBlockCommand
import ai.rojan.backend.application.schedule.CreateSpecialistBlockUseCase
import ai.rojan.backend.application.schedule.CreateSpecialistLeaveCommand
import ai.rojan.backend.application.schedule.CreateSpecialistLeaveUseCase
import ai.rojan.backend.application.schedule.RemoveScheduleOverrideCommand
import ai.rojan.backend.application.schedule.RemoveScheduleOverrideUseCase
import ai.rojan.backend.application.schedule.RemoveSpecialistBlockCommand
import ai.rojan.backend.application.schedule.RemoveSpecialistBlockUseCase
import ai.rojan.backend.application.schedule.RemoveSpecialistLeaveCommand
import ai.rojan.backend.application.schedule.RemoveSpecialistLeaveUseCase
import ai.rojan.backend.application.schedule.RemoveWeeklyAvailabilityCommand
import ai.rojan.backend.application.schedule.RemoveSpecialistWeeklyAvailabilityUseCase
import ai.rojan.backend.application.schedule.SetScheduleOverrideCommand
import ai.rojan.backend.application.schedule.SetScheduleOverrideUseCase
import ai.rojan.backend.application.schedule.SetSpecialistWeeklyAvailabilityUseCase
import ai.rojan.backend.application.schedule.SetWeeklyAvailabilityCommand
import ai.rojan.backend.domain.common.ScheduleOverrideNotFoundException
import ai.rojan.backend.domain.common.SpecialistBlockNotFoundException
import ai.rojan.backend.domain.common.SpecialistLeaveNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.common.WeeklyAvailabilityNotFoundException
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.BlockId
import ai.rojan.backend.domain.schedule.LeaveId
import ai.rojan.backend.domain.schedule.ScheduleOverrideId
import ai.rojan.backend.domain.schedule.SpecialistBlock
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.SpecialistLeave
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverride
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.DayOfWeek
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons/{salonId}/specialists/{specialistId}/schedule")
@Tag(name = "Specialist Schedule")
class SpecialistScheduleController(
    private val specialistRepository: SpecialistRepository,
    private val salonRepository: SalonRepository,
    private val weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
    private val overrideRepository: SpecialistScheduleOverrideRepository,
    private val leaveRepository: SpecialistLeaveRepository,
    private val blockRepository: SpecialistBlockRepository,
    private val setWeeklyAvailabilityUseCase: SetSpecialistWeeklyAvailabilityUseCase,
    private val removeWeeklyAvailabilityUseCase: RemoveSpecialistWeeklyAvailabilityUseCase,
    private val setScheduleOverrideUseCase: SetScheduleOverrideUseCase,
    private val removeScheduleOverrideUseCase: RemoveScheduleOverrideUseCase,
    private val createLeaveUseCase: CreateSpecialistLeaveUseCase,
    private val removeLeaveUseCase: RemoveSpecialistLeaveUseCase,
    private val createBlockUseCase: CreateSpecialistBlockUseCase,
    private val removeBlockUseCase: RemoveSpecialistBlockUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    // ---- Weekly availability ----

    @PutMapping("/weekly-availability/{dayOfWeek}")
    @Operation(summary = "Set a specialist's recurring weekly availability for a day (owner only)")
    fun setWeeklyAvailability(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable dayOfWeek: DayOfWeek,
        @Valid @RequestBody request: SetWeeklyAvailabilityRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): WeeklyAvailabilityResponse {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val availability = setWeeklyAvailabilityUseCase.execute(
            SetWeeklyAvailabilityCommand(specialist.id, callerId, dayOfWeek, request.intervals.map { TimeInterval(it.start, it.end) }),
        )
        return availability.toResponse()
    }

    @GetMapping("/weekly-availability")
    @Operation(summary = "List a specialist's recurring weekly availability")
    fun listWeeklyAvailability(@PathVariable salonId: UUID, @PathVariable specialistId: UUID): List<WeeklyAvailabilityResponse> {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        return weeklyAvailabilityRepository.findBySpecialistId(specialist.id).map { it.toResponse() }
    }

    @GetMapping("/weekly-availability/{dayOfWeek}")
    @Operation(summary = "Get a specialist's recurring weekly availability for one day")
    fun getWeeklyAvailability(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable dayOfWeek: DayOfWeek,
    ): WeeklyAvailabilityResponse {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        return (weeklyAvailabilityRepository.findBySpecialistIdAndDayOfWeek(specialist.id, dayOfWeek)
            ?: throw WeeklyAvailabilityNotFoundException("$specialistId/$dayOfWeek")).toResponse()
    }

    @DeleteMapping("/weekly-availability/{dayOfWeek}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Clear a specialist's weekly availability for a day (owner only)")
    fun removeWeeklyAvailability(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable dayOfWeek: DayOfWeek,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        removeWeeklyAvailabilityUseCase.execute(RemoveWeeklyAvailabilityCommand(specialist.id, callerId, dayOfWeek))
    }

    // ---- Overrides ----

    @PutMapping("/overrides/{date}")
    @Operation(summary = "Set a one-off override of a specialist's availability for a date (owner only)")
    fun setOverride(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable date: java.time.LocalDate,
        @Valid @RequestBody request: SetScheduleOverrideRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ScheduleOverrideResponse {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val override = setScheduleOverrideUseCase.execute(
            SetScheduleOverrideCommand(specialist.id, callerId, date, request.intervals.map { TimeInterval(it.start, it.end) }, request.reason),
        )
        return override.toResponse()
    }

    @GetMapping("/overrides")
    @Operation(
        summary = "List a specialist's schedule overrides",
        description = "The `reason` field is only populated for the salon owner; other viewers see it redacted as null.",
    )
    fun listOverrides(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<ScheduleOverrideResponse> {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val redact = !isOwner(specialist, principal)
        return overrideRepository.findBySpecialistId(specialist.id).map { it.toResponse(redact) }
    }

    @DeleteMapping("/overrides/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a specialist schedule override (owner only)")
    fun removeOverride(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable overrideId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        findOverrideOrThrow(salonId, specialistId, overrideId)
        removeScheduleOverrideUseCase.execute(RemoveScheduleOverrideCommand(ScheduleOverrideId(overrideId), callerId))
    }

    // ---- Leave ----

    @PostMapping("/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a specialist's vacation/leave date range (owner only)")
    fun createLeave(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @Valid @RequestBody request: CreateLeaveRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): LeaveResponse {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val leave = createLeaveUseCase.execute(
            CreateSpecialistLeaveCommand(specialist.id, callerId, request.startDate, request.endDate, request.reason),
        )
        return leave.toResponse()
    }

    @GetMapping("/leaves")
    @Operation(
        summary = "List a specialist's vacation/leave records",
        description = "The `reason` field is only populated for the salon owner; other viewers see it redacted as null.",
    )
    fun listLeaves(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<LeaveResponse> {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val redact = !isOwner(specialist, principal)
        return leaveRepository.findBySpecialistId(specialist.id).map { it.toResponse(redact) }
    }

    @DeleteMapping("/leaves/{leaveId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a specialist's leave record (owner only)")
    fun removeLeave(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable leaveId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        findLeaveOrThrow(salonId, specialistId, leaveId)
        removeLeaveUseCase.execute(RemoveSpecialistLeaveCommand(LeaveId(leaveId), callerId))
    }

    // ---- Manual blocks ----

    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an ad-hoc blocked time window for a specialist (owner only)")
    fun createBlock(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @Valid @RequestBody request: CreateBlockRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): BlockResponse {
        val callerId = currentUserResolver.resolve(principal)
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val block = createBlockUseCase.execute(
            CreateSpecialistBlockCommand(specialist.id, callerId, request.date, TimeInterval(request.start, request.end), request.reason),
        )
        return block.toResponse()
    }

    @GetMapping("/blocks")
    @Operation(
        summary = "List a specialist's ad-hoc blocked time windows",
        description = "The `reason` field is only populated for the salon owner; other viewers see it redacted as null.",
    )
    fun listBlocks(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<BlockResponse> {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        val redact = !isOwner(specialist, principal)
        return blockRepository.findBySpecialistId(specialist.id).map { it.toResponse(redact) }
    }

    @DeleteMapping("/blocks/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a specialist's ad-hoc block (owner only)")
    fun removeBlock(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @PathVariable blockId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        findBlockOrThrow(salonId, specialistId, blockId)
        removeBlockUseCase.execute(RemoveSpecialistBlockCommand(BlockId(blockId), callerId))
    }

    // ---- helpers ----

    private fun findSpecialistOrThrow(salonId: UUID, specialistId: UUID) =
        specialistRepository.findById(SpecialistId(specialistId))
            ?.takeIf { it.salonId.value == salonId }
            ?: throw SpecialistNotFoundException(specialistId.toString())

    private fun findOverrideOrThrow(salonId: UUID, specialistId: UUID, overrideId: UUID): SpecialistScheduleOverride {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        return overrideRepository.findById(ScheduleOverrideId(overrideId))
            ?.takeIf { it.specialistId == specialist.id }
            ?: throw ScheduleOverrideNotFoundException(overrideId.toString())
    }

    private fun findLeaveOrThrow(salonId: UUID, specialistId: UUID, leaveId: UUID): SpecialistLeave {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        return leaveRepository.findById(LeaveId(leaveId))
            ?.takeIf { it.specialistId == specialist.id }
            ?: throw SpecialistLeaveNotFoundException(leaveId.toString())
    }

    private fun findBlockOrThrow(salonId: UUID, specialistId: UUID, blockId: UUID): SpecialistBlock {
        val specialist = findSpecialistOrThrow(salonId, specialistId)
        return blockRepository.findById(BlockId(blockId))
            ?.takeIf { it.specialistId == specialist.id }
            ?: throw SpecialistBlockNotFoundException(blockId.toString())
    }

    /** Only the salon owner may see why a specialist is unavailable (OWASP API3: excessive data exposure). */
    private fun isOwner(specialist: Specialist, principal: UserDetails): Boolean {
        val callerId = currentUserResolver.resolve(principal)
        val salon = salonRepository.findById(specialist.salonId) ?: return false
        return salon.ownerId == callerId
    }

    private fun SpecialistWeeklyAvailability.toResponse() = WeeklyAvailabilityResponse(
        id = id.value,
        specialistId = specialistId.value,
        dayOfWeek = dayOfWeek,
        intervals = intervals.map { TimeIntervalDto(it.start, it.end) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun SpecialistScheduleOverride.toResponse(redactReason: Boolean = false) = ScheduleOverrideResponse(
        id = id.value,
        specialistId = specialistId.value,
        date = date,
        intervals = intervals.map { TimeIntervalDto(it.start, it.end) },
        reason = if (redactReason) null else reason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun SpecialistLeave.toResponse(redactReason: Boolean = false) = LeaveResponse(
        id = id.value,
        specialistId = specialistId.value,
        startDate = startDate,
        endDate = endDate,
        reason = if (redactReason) null else reason,
        createdAt = createdAt,
    )

    private fun SpecialistBlock.toResponse(redactReason: Boolean = false) = BlockResponse(
        id = id.value,
        specialistId = specialistId.value,
        date = date,
        start = interval.start,
        end = interval.end,
        reason = if (redactReason) null else reason,
        createdAt = createdAt,
    )
}
