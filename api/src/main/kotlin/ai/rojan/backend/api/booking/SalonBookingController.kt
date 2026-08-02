package ai.rojan.backend.api.booking

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons/{salonId}/bookings")
@Tag(name = "Bookings")
class SalonBookingController(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "List all bookings for a salon (owner only)")
    fun list(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): List<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val salon = salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())
        if (salon.ownerId != callerId) throw SalonAccessDeniedException(salon.id.value.toString())
        return bookingRepository.findBySalonId(salon.id).map { it.toResponse() }
    }

    private fun Booking.toResponse() = BookingResponse(
        id = id.value,
        salonId = salonId.value,
        serviceId = serviceId.value,
        specialistId = specialistId.value,
        customerId = customerId.value,
        startTime = startTime,
        endTime = endTime,
        status = status,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
