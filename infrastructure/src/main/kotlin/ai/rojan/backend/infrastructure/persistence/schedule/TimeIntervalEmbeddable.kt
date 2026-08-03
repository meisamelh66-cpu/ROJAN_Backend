package ai.rojan.backend.infrastructure.persistence.schedule

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.LocalTime

@Embeddable
class TimeIntervalEmbeddable(
    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime,

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime,
)
