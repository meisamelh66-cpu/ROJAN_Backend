package ai.rojan.backend.domain.salon

import java.time.Instant
import java.util.UUID

@JvmInline
value class SpecialistServiceId(val value: UUID) {
    companion object {
        fun new(): SpecialistServiceId = SpecialistServiceId(UUID.randomUUID())
    }
}

/**
 * Records that [specialistId] is eligible to perform [serviceId]. Absence of
 * any [SpecialistService] rows for a given specialist means "eligible for
 * everything" (see [isSpecialistEligibleForService]) — this keeps every
 * specialist that existed before this feature, and every specialist that
 * never opts in, fully backward compatible.
 */
class SpecialistService private constructor(
    val id: SpecialistServiceId,
    val specialistId: SpecialistId,
    val serviceId: ServiceId,
    val createdAt: Instant,
) {
    companion object {
        fun create(specialistId: SpecialistId, serviceId: ServiceId): SpecialistService =
            SpecialistService(SpecialistServiceId.new(), specialistId, serviceId, Instant.now())

        fun reconstitute(
            id: SpecialistServiceId,
            specialistId: SpecialistId,
            serviceId: ServiceId,
            createdAt: Instant,
        ): SpecialistService = SpecialistService(id, specialistId, serviceId, createdAt)
    }
}

interface SpecialistServiceRepository {
    fun assign(specialistId: SpecialistId, serviceId: ServiceId): SpecialistService
    fun remove(specialistId: SpecialistId, serviceId: ServiceId)
    fun findServiceIdsBySpecialistId(specialistId: SpecialistId): Set<ServiceId>
}

/**
 * The single source of truth for the opt-in eligibility rule, called from
 * both [ai.rojan.backend.application.booking.BookingUseCases.CreateBookingUseCase]
 * and [ai.rojan.backend.application.booking.GetAvailableSlotsUseCase] so the
 * rule can never drift between the two call sites. An empty [assignedServiceIds]
 * means the specialist never opted into eligibility restrictions and remains
 * bookable for anything — the backward-compatible default.
 */
fun isSpecialistEligibleForService(assignedServiceIds: Set<ServiceId>, serviceId: ServiceId): Boolean =
    assignedServiceIds.isEmpty() || serviceId in assignedServiceIds
