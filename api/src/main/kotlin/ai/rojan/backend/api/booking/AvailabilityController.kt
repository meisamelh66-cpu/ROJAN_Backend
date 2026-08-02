package ai.rojan.backend.api.booking

import ai.rojan.backend.application.booking.GetAvailableSlotsQuery
import ai.rojan.backend.application.booking.GetAvailableSlotsUseCase
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons/{salonId}/specialists/{specialistId}/available-slots")
@Tag(name = "Availability")
class AvailabilityController(
    private val getAvailableSlotsUseCase: GetAvailableSlotsUseCase,
) {

    @GetMapping
    @Operation(summary = "Compute bookable time slots for a specialist and service on a date")
    fun getAvailableSlots(
        @PathVariable salonId: UUID,
        @PathVariable specialistId: UUID,
        @RequestParam serviceId: UUID,
        @RequestParam date: LocalDate,
        @RequestParam(defaultValue = "15") slotIntervalMinutes: Int,
    ): List<TimeSlotResponse> {
        val slots = getAvailableSlotsUseCase.execute(
            GetAvailableSlotsQuery(
                salonId = SalonId(salonId),
                specialistId = SpecialistId(specialistId),
                serviceId = ServiceId(serviceId),
                date = date,
                slotIntervalMinutes = slotIntervalMinutes,
            ),
        )
        return slots.map { TimeSlotResponse(it.start, it.end) }
    }
}
