package ai.rojan.backend.application.dashboard

import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.AmbiguousSalonContextException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.user.UserId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class GetDashboardInsightsCommand(val callerId: UserId)

data class DashboardInsights(
    val revenue: RevenueInsights,
    val bookings: BookingCountInsights,
    val customers: CustomerInsights,
    val services: List<ServiceInsight>,
    val recommendations: List<Recommendation>,
)

data class RevenueInsights(val today: BigDecimal, val month: BigDecimal, val growthRate: BigDecimal)

data class BookingCountInsights(val total: Long, val completed: Long, val cancelled: Long)

data class CustomerInsights(val newCustomers: Int, val returningCustomers: Int)

data class ServiceInsight(val name: String, val bookings: Long, val revenue: BigDecimal)

private val HUNDRED = BigDecimal(100)

/**
 * "Today"/"this month" are always evaluated against the server's local date
 * (no per-salon timezone configured yet, matching every other date field in
 * this codebase — see [Booking]'s LocalDateTime start/end times).
 */
class GetDashboardInsightsUseCase(
    private val salonRepository: SalonRepository,
    private val bookingRepository: BookingRepository,
    private val serviceRepository: ServiceRepository,
    private val insightEngine: InsightEngine,
) {
    fun execute(command: GetDashboardInsightsCommand): DashboardInsights {
        val ownedSalons = salonRepository.findByOwnerId(command.callerId)
        val salon = when (ownedSalons.size) {
            0 -> throw SalonNotFoundException(command.callerId.value.toString())
            1 -> ownedSalons.single()
            else -> throw AmbiguousSalonContextException(command.callerId.value.toString())
        }

        val today = LocalDate.now()
        val todayStart = today.atStartOfDay()
        val tomorrowStart = today.plusDays(1).atStartOfDay()
        val monthStart = today.withDayOfMonth(1).atStartOfDay()
        val nextMonthStart = monthStart.plusMonths(1)
        val previousMonthStart = monthStart.minusMonths(1)

        val monthBookings = bookingRepository.findBySalonIdAndStartTimeRange(salon.id, monthStart, nextMonthStart)
        val previousMonthBookings = bookingRepository.findBySalonIdAndStartTimeRange(salon.id, previousMonthStart, monthStart)
        val services = serviceRepository.findBySalonId(salon.id).associateBy { it.id }

        val todayBookings = monthBookings.filter { it.startTime >= todayStart && it.startTime < tomorrowStart }
        val monthRevenue = monthBookings.completedRevenue(services)
        val previousMonthRevenue = previousMonthBookings.completedRevenue(services)

        val customerIdsThisMonth = monthBookings.map { it.customerId }.toSet()
        val returningCustomerIds = if (customerIdsThisMonth.isEmpty()) {
            emptySet()
        } else {
            bookingRepository.findCustomerIdsWithBookingBefore(salon.id, customerIdsThisMonth, monthStart)
        }

        val cancelledThisMonth = monthBookings.count { it.status == BookingStatus.CANCELLED }.toLong()
        val cancelledPreviousMonth = previousMonthBookings.count { it.status == BookingStatus.CANCELLED }.toLong()
        val revenueGrowthRate = growthRate(monthRevenue, previousMonthRevenue)
        val newCustomers = customerIdsThisMonth.size - returningCustomerIds.size

        val serviceInsights = monthBookings
            .filter { it.status == BookingStatus.COMPLETED }
            .groupBy { it.serviceId }
            .map { (serviceId, bookingsForService) ->
                val service = services.getValue(serviceId)
                ServiceInsight(
                    name = service.name,
                    bookings = bookingsForService.size.toLong(),
                    revenue = bookingsForService.sumPrice(service),
                )
            }
            .sortedByDescending { it.revenue }

        val recommendations = insightEngine.generate(
            SalonInsightMetrics(
                revenueGrowthRate = revenueGrowthRate,
                bookingsThisMonth = monthBookings.size.toLong(),
                bookingsPreviousMonth = previousMonthBookings.size.toLong(),
                cancelledThisMonth = cancelledThisMonth,
                totalThisMonth = monthBookings.size.toLong(),
                cancelledPreviousMonth = cancelledPreviousMonth,
                totalPreviousMonth = previousMonthBookings.size.toLong(),
                newCustomers = newCustomers,
                returningCustomers = returningCustomerIds.size,
                services = serviceInsights,
            ),
        )

        return DashboardInsights(
            revenue = RevenueInsights(
                today = todayBookings.completedRevenue(services),
                month = monthRevenue,
                growthRate = revenueGrowthRate,
            ),
            bookings = BookingCountInsights(
                total = monthBookings.size.toLong(),
                completed = monthBookings.count { it.status == BookingStatus.COMPLETED }.toLong(),
                cancelled = cancelledThisMonth,
            ),
            customers = CustomerInsights(
                newCustomers = newCustomers,
                returningCustomers = returningCustomerIds.size,
            ),
            services = serviceInsights,
            recommendations = recommendations,
        )
    }

    private fun List<Booking>.completedRevenue(services: Map<ServiceId, Service>): BigDecimal =
        filter { it.status == BookingStatus.COMPLETED }
            .fold(BigDecimal.ZERO) { total, booking -> total + services.getValue(booking.serviceId).price }

    private fun List<Booking>.sumPrice(service: Service): BigDecimal = size.toBigDecimal() * service.price

    private fun growthRate(current: BigDecimal, previous: BigDecimal): BigDecimal = when {
        previous.signum() == 0 -> if (current.signum() > 0) HUNDRED else BigDecimal.ZERO
        else -> current.subtract(previous)
            .divide(previous, 4, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(2, RoundingMode.HALF_UP)
    }
}
