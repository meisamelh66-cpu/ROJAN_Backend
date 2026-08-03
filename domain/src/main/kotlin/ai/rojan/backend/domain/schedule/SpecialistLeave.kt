package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class LeaveId(val value: UUID) {
    companion object {
        fun new(): LeaveId = LeaveId(UUID.randomUUID())
    }
}

/** A date range (inclusive) during which a specialist is fully unavailable, e.g. vacation or sick leave. */
class SpecialistLeave private constructor(
    val id: LeaveId,
    val specialistId: SpecialistId,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String?,
    val createdAt: Instant,
) {
    fun overlaps(date: LocalDate): Boolean = !date.isBefore(startDate) && !date.isAfter(endDate)

    companion object {
        fun create(specialistId: SpecialistId, startDate: LocalDate, endDate: LocalDate, reason: String?): SpecialistLeave {
            require(!startDate.isAfter(endDate)) { "Leave start date must not be after end date" }
            return SpecialistLeave(LeaveId.new(), specialistId, startDate, endDate, reason?.trim()?.ifBlank { null }, Instant.now())
        }

        fun reconstitute(
            id: LeaveId,
            specialistId: SpecialistId,
            startDate: LocalDate,
            endDate: LocalDate,
            reason: String?,
            createdAt: Instant,
        ): SpecialistLeave = SpecialistLeave(id, specialistId, startDate, endDate, reason, createdAt)
    }
}
