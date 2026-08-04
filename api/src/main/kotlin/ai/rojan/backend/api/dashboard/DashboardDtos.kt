package ai.rojan.backend.api.dashboard

import ai.rojan.backend.application.dashboard.RecommendationPriority
import ai.rojan.backend.application.dashboard.RecommendationType
import java.math.BigDecimal

data class DashboardInsightsResponse(
    val revenue: RevenueResponse,
    val bookings: BookingCountsResponse,
    val customers: CustomerCountsResponse,
    val services: List<ServiceInsightResponse>,
    val recommendations: List<RecommendationResponse>,
)

data class RevenueResponse(val today: BigDecimal, val month: BigDecimal, val growthRate: BigDecimal)

data class BookingCountsResponse(val total: Long, val completed: Long, val cancelled: Long)

data class CustomerCountsResponse(val newCustomers: Int, val returningCustomers: Int)

data class ServiceInsightResponse(val name: String, val bookings: Long, val revenue: BigDecimal)

data class RecommendationResponse(val type: RecommendationType, val priority: RecommendationPriority, val message: String)
