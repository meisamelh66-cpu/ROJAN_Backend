package ai.rojan.backend.api.config

import ai.rojan.backend.application.dashboard.GetDashboardInsightsUseCase
import ai.rojan.backend.application.dashboard.InsightEngine
import ai.rojan.backend.application.dashboard.RuleBasedRecommendationEngine
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free dashboard application use cases as Spring beans, mirroring [SalonUseCaseConfig]. */
@Configuration
class DashboardUseCaseConfig {

    /** v1 of the AI insight layer. Swap this bean for a model-backed [InsightEngine] later without touching callers. */
    @Bean
    fun insightEngine(): InsightEngine = RuleBasedRecommendationEngine()

    @Bean
    fun getDashboardInsightsUseCase(
        salonRepository: SalonRepository,
        bookingRepository: BookingRepository,
        serviceRepository: ServiceRepository,
        insightEngine: InsightEngine,
        salonPermissionResolver: SalonPermissionResolver,
    ) = GetDashboardInsightsUseCase(salonRepository, bookingRepository, serviceRepository, insightEngine, salonPermissionResolver)
}
