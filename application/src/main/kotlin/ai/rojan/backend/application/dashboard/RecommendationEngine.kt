package ai.rojan.backend.application.dashboard

import java.math.BigDecimal

enum class RecommendationType {
    REVENUE_GROWTH,
    REVENUE_DECLINE,
    BOOKING_GROWTH,
    BOOKING_DECLINE,
    CANCELLATION_RATE,
    CUSTOMER_RETENTION_LOW,
    CUSTOMER_RETENTION_HIGH,
    SERVICE_PERFORMANCE,
}

enum class RecommendationPriority {
    LOW,
    MEDIUM,
    HIGH,
}

data class Recommendation(
    val type: RecommendationType,
    val priority: RecommendationPriority,
    val message: String,
)

/**
 * The numbers an [InsightEngine] reasons over — a snapshot of one salon's
 * current vs. previous month, decoupled from [DashboardInsights] (the public
 * API shape) so the engine's input contract doesn't change every time the
 * response DTO does.
 */
data class SalonInsightMetrics(
    val revenueGrowthRate: BigDecimal,
    val bookingsThisMonth: Long,
    val bookingsPreviousMonth: Long,
    val cancelledThisMonth: Long,
    val totalThisMonth: Long,
    val cancelledPreviousMonth: Long,
    val totalPreviousMonth: Long,
    val newCustomers: Int,
    val returningCustomers: Int,
    val services: List<ServiceInsight>,
)

/**
 * Turns [SalonInsightMetrics] into actionable, human-readable recommendations
 * — the "Insight Engine" stage of `Metrics -> Insight Engine -> AI
 * Recommendations`. [RuleBasedRecommendationEngine] is v1; this interface is
 * the seam a future model-backed implementation plugs into without touching
 * [GetDashboardInsightsUseCase] or the API layer.
 */
interface InsightEngine {
    fun generate(metrics: SalonInsightMetrics): List<Recommendation>
}
