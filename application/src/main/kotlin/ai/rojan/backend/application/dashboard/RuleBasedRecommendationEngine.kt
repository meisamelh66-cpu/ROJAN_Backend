package ai.rojan.backend.application.dashboard

import java.math.BigDecimal
import java.math.RoundingMode

private val CANCELLATION_WARNING_THRESHOLD = BigDecimal("0.15")
private val LOW_RETENTION_THRESHOLD = BigDecimal("0.20")
private val HIGH_RETENTION_THRESHOLD = BigDecimal("0.50")

/**
 * ROJAN AI Recommendation Engine v1 — deterministic, rule-based, no external
 * LLM/API call. Each rule reads one slice of [SalonInsightMetrics] and either
 * emits a [Recommendation] or stays silent; rules are independent, so adding
 * or removing one never affects the others.
 */
class RuleBasedRecommendationEngine : InsightEngine {

    override fun generate(metrics: SalonInsightMetrics): List<Recommendation> =
        listOfNotNull(
            revenueRule(metrics),
            bookingVolumeRule(metrics),
            cancellationRateRule(metrics),
            customerRetentionRule(metrics),
            topServiceRule(metrics),
        )

    private fun revenueRule(metrics: SalonInsightMetrics): Recommendation? = when {
        metrics.revenueGrowthRate.signum() > 0 -> Recommendation(
            type = RecommendationType.REVENUE_GROWTH,
            priority = if (metrics.revenueGrowthRate >= BigDecimal(20)) RecommendationPriority.HIGH else RecommendationPriority.MEDIUM,
            message = "درآمد شما نسبت به دوره قبل رشد داشته است.",
        )
        metrics.revenueGrowthRate.signum() < 0 -> Recommendation(
            type = RecommendationType.REVENUE_DECLINE,
            priority = RecommendationPriority.HIGH,
            message = "درآمد شما نسبت به دوره قبل کاهش یافته است. بررسی دلایل کاهش رزرو پیشنهاد می‌شود.",
        )
        else -> null
    }

    private fun bookingVolumeRule(metrics: SalonInsightMetrics): Recommendation? = when {
        metrics.bookingsThisMonth > metrics.bookingsPreviousMonth -> Recommendation(
            type = RecommendationType.BOOKING_GROWTH,
            priority = RecommendationPriority.MEDIUM,
            message = "تعداد رزروها نسبت به دوره قبل افزایش یافته است.",
        )
        metrics.bookingsThisMonth < metrics.bookingsPreviousMonth -> Recommendation(
            type = RecommendationType.BOOKING_DECLINE,
            priority = RecommendationPriority.MEDIUM,
            message = "تعداد رزروها نسبت به دوره قبل کاهش یافته است.",
        )
        else -> null
    }

    /** Only fires when there's a prior-period baseline to actually compare against — no baseline, no false "increase" claim. */
    private fun cancellationRateRule(metrics: SalonInsightMetrics): Recommendation? {
        if (metrics.totalThisMonth == 0L || metrics.totalPreviousMonth == 0L) return null
        val currentRate = rate(metrics.cancelledThisMonth, metrics.totalThisMonth)
        val previousRate = rate(metrics.cancelledPreviousMonth, metrics.totalPreviousMonth)
        if (currentRate <= previousRate || currentRate <= CANCELLATION_WARNING_THRESHOLD) return null
        return Recommendation(
            type = RecommendationType.CANCELLATION_RATE,
            priority = RecommendationPriority.HIGH,
            message = "نرخ لغو نوبت افزایش یافته. پیشنهاد: فعال‌سازی یادآوری پیامکی.",
        )
    }

    private fun customerRetentionRule(metrics: SalonInsightMetrics): Recommendation? {
        val totalCustomers = metrics.newCustomers + metrics.returningCustomers
        if (totalCustomers == 0) return null
        val returningRatio = rate(metrics.returningCustomers.toLong(), totalCustomers.toLong())
        return when {
            returningRatio < LOW_RETENTION_THRESHOLD -> Recommendation(
                type = RecommendationType.CUSTOMER_RETENTION_LOW,
                priority = RecommendationPriority.MEDIUM,
                message = "نرخ بازگشت مشتریان پایین است. پیشنهاد: ارسال کد تخفیف برای مشتریان قبلی.",
            )
            returningRatio >= HIGH_RETENTION_THRESHOLD -> Recommendation(
                type = RecommendationType.CUSTOMER_RETENTION_HIGH,
                priority = RecommendationPriority.LOW,
                message = "نرخ بازگشت مشتریان مطلوب است.",
            )
            else -> null
        }
    }

    private fun topServiceRule(metrics: SalonInsightMetrics): Recommendation? {
        val topService = metrics.services.maxByOrNull { it.revenue } ?: return null
        return Recommendation(
            type = RecommendationType.SERVICE_PERFORMANCE,
            priority = RecommendationPriority.LOW,
            message = "پرمخاطب‌ترین خدمت این ماه: ${topService.name}",
        )
    }

    private fun rate(part: Long, whole: Long): BigDecimal =
        part.toBigDecimal().divide(whole.toBigDecimal(), 4, RoundingMode.HALF_UP)
}
