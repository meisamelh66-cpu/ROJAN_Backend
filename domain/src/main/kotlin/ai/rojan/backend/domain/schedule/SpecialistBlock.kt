package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class BlockId(val value: UUID) {
    companion object {
        fun new(): BlockId = BlockId(UUID.randomUUID())
    }
}

/** An ad-hoc blocked time window on a specific date (e.g. a personal errand), without taking a full-day [SpecialistLeave]. */
class SpecialistBlock private constructor(
    val id: BlockId,
    val specialistId: SpecialistId,
    val date: LocalDate,
    val interval: TimeInterval,
    val reason: String?,
    val createdAt: Instant,
) {
    companion object {
        fun create(specialistId: SpecialistId, date: LocalDate, interval: TimeInterval, reason: String?): SpecialistBlock =
            SpecialistBlock(BlockId.new(), specialistId, date, interval, reason?.trim()?.ifBlank { null }, Instant.now())

        fun reconstitute(
            id: BlockId,
            specialistId: SpecialistId,
            date: LocalDate,
            interval: TimeInterval,
            reason: String?,
            createdAt: Instant,
        ): SpecialistBlock = SpecialistBlock(id, specialistId, date, interval, reason, createdAt)
    }
}
