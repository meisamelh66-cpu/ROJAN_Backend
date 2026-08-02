package ai.rojan.backend.api.booking

import java.time.LocalDateTime

data class TimeSlotResponse(val start: LocalDateTime, val end: LocalDateTime)
