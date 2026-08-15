package ai.rojan.backend.api.publicsalon

import ai.rojan.backend.api.booking.TimeSlotResponse
import ai.rojan.backend.application.booking.GetAvailableSlotsQuery
import ai.rojan.backend.application.booking.GetAvailableSlotsUseCase
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * The customer QR journey's entry point - no authentication, resolved by
 * [Salon.slug] rather than id. Lives under the already permit-all public
 * prefix in `SecurityConfig` (previously only backing a stub), so no
 * security-filter change was needed for this. Thin pass-throughs to the
 * same repositories/use cases the authenticated salon-management endpoints
 * use - active rows only, and deliberately separate DTOs
 * ([PublicSalonResponse] et al.) that never leak `ownerId`/`userId`.
 * Booking itself is unchanged and still requires the existing OTP-issued
 * JWT - browsing is public, booking is not.
 */
@RestController
@RequestMapping("/api/v1/public/salons/{slug}")
@Tag(name = "Public Salon")
class PublicSalonController(
    private val salonRepository: SalonRepository,
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistRepository: SpecialistRepository,
    private val getAvailableSlotsUseCase: GetAvailableSlotsUseCase,
) {

    @GetMapping
    @Operation(summary = "Get a salon's public profile by its slug")
    fun get(@PathVariable slug: String): PublicSalonResponse = findSalonOrThrow(slug).toResponse()

    @GetMapping("/categories")
    @Operation(summary = "Browse a salon's active service categories")
    fun categories(@PathVariable slug: String): List<PublicServiceCategoryResponse> {
        val salon = findSalonOrThrow(slug)
        return serviceCategoryRepository.findBySalonId(salon.id).filter { it.active }.map { it.toResponse() }
    }

    @GetMapping("/categories/{categoryId}/services")
    @Operation(summary = "Browse a salon's active services within a category")
    fun services(@PathVariable slug: String, @PathVariable categoryId: UUID): List<PublicServiceResponse> {
        val salon = findSalonOrThrow(slug)
        return serviceRepository.findByCategoryId(ServiceCategoryId(categoryId))
            .filter { it.salonId == salon.id && it.active }
            .map { it.toResponse() }
    }

    @GetMapping("/specialists")
    @Operation(summary = "Browse a salon's active specialists")
    fun specialists(@PathVariable slug: String): List<PublicSpecialistResponse> {
        val salon = findSalonOrThrow(slug)
        return specialistRepository.findBySalonId(salon.id).filter { it.active }.map { it.toResponse() }
    }

    @GetMapping("/specialists/{specialistId}/available-slots")
    @Operation(summary = "Compute bookable time slots for a specialist and service on a date - same engine as the authenticated endpoint")
    fun availableSlots(
        @PathVariable slug: String,
        @PathVariable specialistId: UUID,
        @RequestParam serviceId: UUID,
        @RequestParam date: LocalDate,
        @RequestParam(defaultValue = "15") slotIntervalMinutes: Int,
    ): List<TimeSlotResponse> {
        val salon = findSalonOrThrow(slug)
        val slots = getAvailableSlotsUseCase.execute(
            GetAvailableSlotsQuery(
                salonId = salon.id,
                specialistId = SpecialistId(specialistId),
                serviceId = ServiceId(serviceId),
                date = date,
                slotIntervalMinutes = slotIntervalMinutes,
            ),
        )
        return slots.map { TimeSlotResponse(it.start, it.end) }
    }

    /** A DRAFT salon 404s identically to an unknown slug - it must never be distinguishable as "exists but not ready" to an anonymous caller. */
    private fun findSalonOrThrow(slug: String): Salon =
        salonRepository.findBySlug(slug)
            ?.takeIf { it.active && it.onboardingStatus == SalonOnboardingStatus.ACTIVE }
            ?: throw SalonNotFoundException(slug)

    private fun Salon.toResponse() = PublicSalonResponse(id.value, name, description, phone, address, logoUrl, latitude, longitude)

    private fun ServiceCategory.toResponse() = PublicServiceCategoryResponse(id.value, name, description)

    private fun Service.toResponse() = PublicServiceResponse(id.value, categoryId.value, name, description, durationMinutes, price)

    private fun Specialist.toResponse() = PublicSpecialistResponse(id.value, displayName, bio, photoUrl)
}
