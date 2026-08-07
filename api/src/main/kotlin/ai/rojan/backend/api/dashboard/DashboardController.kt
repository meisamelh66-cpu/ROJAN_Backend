package ai.rojan.backend.api.dashboard

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.dashboard.DashboardInsights
import ai.rojan.backend.application.dashboard.GetDashboardInsightsCommand
import ai.rojan.backend.application.dashboard.GetDashboardInsightsUseCase
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
class DashboardController(
    private val getDashboardInsightsUseCase: GetDashboardInsightsUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping("/insights")
    @Operation(
        summary = "Revenue, booking, customer and per-service insights for a salon owned by the authenticated user",
        description = "salonId is optional - when omitted, resolves the caller's single salon implicitly (fails " +
            "with 409 if the caller owns more than one). Owners of multiple salons must pass salonId explicitly.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Insights computed"),
        ApiResponse(responseCode = "401", description = "Missing or invalid bearer token"),
        ApiResponse(
            responseCode = "403",
            description = "salonId was supplied but does not belong to the caller",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Caller does not own a salon, or the supplied salonId does not exist",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "salonId was omitted and the caller owns more than one salon; context cannot be resolved implicitly",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun insights(
        @Parameter(description = "Optional - disambiguates which salon when the caller owns more than one")
        @RequestParam(required = false) salonId: UUID?,
        @AuthenticationPrincipal principal: UserDetails,
    ): DashboardInsightsResponse {
        val callerId = currentUserResolver.resolve(principal)
        val insights = getDashboardInsightsUseCase.execute(GetDashboardInsightsCommand(callerId, salonId?.let { SalonId(it) }))
        return insights.toResponse()
    }

    private fun DashboardInsights.toResponse() = DashboardInsightsResponse(
        revenue = RevenueResponse(revenue.today, revenue.month, revenue.growthRate),
        bookings = BookingCountsResponse(bookings.total, bookings.completed, bookings.cancelled),
        customers = CustomerCountsResponse(customers.newCustomers, customers.returningCustomers),
        services = services.map { ServiceInsightResponse(it.name, it.bookings, it.revenue) },
        recommendations = recommendations.map { RecommendationResponse(it.type, it.priority, it.message) },
    )
}
