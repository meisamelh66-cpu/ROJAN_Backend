package ai.rojan.backend.application.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

private val ZERO_METRICS = SalonInsightMetrics(
    revenueGrowthRate = BigDecimal.ZERO,
    bookingsThisMonth = 0,
    bookingsPreviousMonth = 0,
    cancelledThisMonth = 0,
    totalThisMonth = 0,
    cancelledPreviousMonth = 0,
    totalPreviousMonth = 0,
    newCustomers = 0,
    returningCustomers = 0,
    services = emptyList(),
)

class RuleBasedRecommendationEngineTest {

    private val engine = RuleBasedRecommendationEngine()

    @Test
    fun `no data produces no recommendations`() {
        assertTrue(engine.generate(ZERO_METRICS).isEmpty())
    }

    @Test
    fun `moderate revenue growth is medium priority`() {
        val metrics = ZERO_METRICS.copy(revenueGrowthRate = BigDecimal("10"))
        val recommendation = engine.generate(metrics).single { it.type == RecommendationType.REVENUE_GROWTH }
        assertEquals(RecommendationPriority.MEDIUM, recommendation.priority)
    }

    @Test
    fun `strong revenue growth is high priority`() {
        val metrics = ZERO_METRICS.copy(revenueGrowthRate = BigDecimal("25"))
        val recommendation = engine.generate(metrics).single { it.type == RecommendationType.REVENUE_GROWTH }
        assertEquals(RecommendationPriority.HIGH, recommendation.priority)
    }

    @Test
    fun `revenue decline is flagged high priority`() {
        val metrics = ZERO_METRICS.copy(revenueGrowthRate = BigDecimal("-15"))
        val recommendation = engine.generate(metrics).single { it.type == RecommendationType.REVENUE_DECLINE }
        assertEquals(RecommendationPriority.HIGH, recommendation.priority)
    }

    @Test
    fun `flat revenue produces no revenue recommendation`() {
        val metrics = ZERO_METRICS.copy(revenueGrowthRate = BigDecimal.ZERO)
        assertNull(engine.generate(metrics).find { it.type == RecommendationType.REVENUE_GROWTH || it.type == RecommendationType.REVENUE_DECLINE })
    }

    @Test
    fun `booking count increase is flagged`() {
        val metrics = ZERO_METRICS.copy(bookingsThisMonth = 20, bookingsPreviousMonth = 10)
        assertTrue(engine.generate(metrics).any { it.type == RecommendationType.BOOKING_GROWTH })
    }

    @Test
    fun `booking count decrease is flagged`() {
        val metrics = ZERO_METRICS.copy(bookingsThisMonth = 5, bookingsPreviousMonth = 10)
        assertTrue(engine.generate(metrics).any { it.type == RecommendationType.BOOKING_DECLINE })
    }

    @Test
    fun `cancellation rate rising above threshold is flagged high priority`() {
        val metrics = ZERO_METRICS.copy(
            cancelledThisMonth = 4,
            totalThisMonth = 10,
            cancelledPreviousMonth = 1,
            totalPreviousMonth = 10,
        )
        val recommendation = engine.generate(metrics).single { it.type == RecommendationType.CANCELLATION_RATE }
        assertEquals(RecommendationPriority.HIGH, recommendation.priority)
    }

    @Test
    fun `cancellation rate rising but still under threshold is not flagged`() {
        val metrics = ZERO_METRICS.copy(
            cancelledThisMonth = 1,
            totalThisMonth = 10,
            cancelledPreviousMonth = 0,
            totalPreviousMonth = 10,
        )
        assertNull(engine.generate(metrics).find { it.type == RecommendationType.CANCELLATION_RATE })
    }

    @Test
    fun `cancellation rate not increasing is not flagged even if high`() {
        val metrics = ZERO_METRICS.copy(
            cancelledThisMonth = 4,
            totalThisMonth = 10,
            cancelledPreviousMonth = 5,
            totalPreviousMonth = 10,
        )
        assertNull(engine.generate(metrics).find { it.type == RecommendationType.CANCELLATION_RATE })
    }

    @Test
    fun `cancellation rate with no previous-month baseline is not flagged`() {
        val metrics = ZERO_METRICS.copy(cancelledThisMonth = 8, totalThisMonth = 10, totalPreviousMonth = 0)
        assertNull(engine.generate(metrics).find { it.type == RecommendationType.CANCELLATION_RATE })
    }

    @Test
    fun `low customer retention is flagged`() {
        val metrics = ZERO_METRICS.copy(newCustomers = 9, returningCustomers = 1)
        assertTrue(engine.generate(metrics).any { it.type == RecommendationType.CUSTOMER_RETENTION_LOW })
    }

    @Test
    fun `high customer retention is flagged positively`() {
        val metrics = ZERO_METRICS.copy(newCustomers = 2, returningCustomers = 8)
        assertTrue(engine.generate(metrics).any { it.type == RecommendationType.CUSTOMER_RETENTION_HIGH })
    }

    @Test
    fun `mid-range retention produces no retention recommendation`() {
        val metrics = ZERO_METRICS.copy(newCustomers = 6, returningCustomers = 4)
        assertNull(
            engine.generate(metrics)
                .find { it.type == RecommendationType.CUSTOMER_RETENTION_LOW || it.type == RecommendationType.CUSTOMER_RETENTION_HIGH },
        )
    }

    @Test
    fun `top service by revenue is surfaced by name`() {
        val metrics = ZERO_METRICS.copy(
            services = listOf(
                ServiceInsight("Haircut", bookings = 5, revenue = BigDecimal("100")),
                ServiceInsight("Massage", bookings = 2, revenue = BigDecimal("400")),
            ),
        )
        val recommendation = engine.generate(metrics).single { it.type == RecommendationType.SERVICE_PERFORMANCE }
        assertTrue(recommendation.message.contains("Massage"))
    }
}
