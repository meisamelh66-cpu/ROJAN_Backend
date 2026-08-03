package ai.rojan.backend.api.schedule

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.schedule.RemoveWorkingHoursCommand
import ai.rojan.backend.application.schedule.RemoveWorkingHoursUseCase
import ai.rojan.backend.application.schedule.SetWorkingHoursCommand
import ai.rojan.backend.application.schedule.SetWorkingHoursUseCase
import ai.rojan.backend.domain.common.WorkingHoursNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.DayOfWeek
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons/{salonId}/working-hours")
@Tag(name = "Working Hours")
class WorkingHoursController(
    private val workingHoursRepository: WorkingHoursRepository,
    private val setWorkingHoursUseCase: SetWorkingHoursUseCase,
    private val removeWorkingHoursUseCase: RemoveWorkingHoursUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PutMapping("/{dayOfWeek}")
    @Operation(summary = "Set a salon's operating hours for a day of the week (owner only)")
    fun set(
        @PathVariable salonId: UUID,
        @PathVariable dayOfWeek: DayOfWeek,
        @Valid @RequestBody request: SetWorkingHoursRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): WorkingHoursResponse {
        val callerId = currentUserResolver.resolve(principal)
        val workingHours = setWorkingHoursUseCase.execute(
            SetWorkingHoursCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                dayOfWeek = dayOfWeek,
                intervals = request.intervals.map { TimeInterval(it.start, it.end) },
            ),
        )
        return workingHours.toResponse()
    }

    @GetMapping
    @Operation(summary = "List a salon's operating hours for every configured day")
    fun list(@PathVariable salonId: UUID): List<WorkingHoursResponse> =
        workingHoursRepository.findBySalonId(SalonId(salonId)).map { it.toResponse() }

    @GetMapping("/{dayOfWeek}")
    @Operation(summary = "Get a salon's operating hours for one day of the week")
    fun get(@PathVariable salonId: UUID, @PathVariable dayOfWeek: DayOfWeek): WorkingHoursResponse =
        (workingHoursRepository.findBySalonIdAndDayOfWeek(SalonId(salonId), dayOfWeek)
            ?: throw WorkingHoursNotFoundException("$salonId/$dayOfWeek")).toResponse()

    @DeleteMapping("/{dayOfWeek}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark a salon closed on a day of the week (owner only)")
    fun remove(
        @PathVariable salonId: UUID,
        @PathVariable dayOfWeek: DayOfWeek,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        removeWorkingHoursUseCase.execute(RemoveWorkingHoursCommand(SalonId(salonId), callerId, dayOfWeek))
    }

    private fun WorkingHours.toResponse() = WorkingHoursResponse(
        id = id.value,
        salonId = salonId.value,
        dayOfWeek = dayOfWeek,
        intervals = intervals.map { TimeIntervalDto(it.start, it.end) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
