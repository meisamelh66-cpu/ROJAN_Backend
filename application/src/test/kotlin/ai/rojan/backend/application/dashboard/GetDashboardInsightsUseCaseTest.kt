package ai.rojan.backend.application.dashboard

import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.AmbiguousSalonContextException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class GetDashboardInsightsUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val bookingRepository = InMemoryBookingRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val useCase = GetDashboardInsightsUseCase(
        salonRepository,
        bookingRepository,
        serviceRepository,
        RuleBasedRecommendationEngine(),
        salonPermissionResolver,
    )

    private fun salonOwnedBy(ownerId: UserId): Salon =
        Salon.create(ownerId, "Test Salon", null, "+1 555 0100", null, "1 Main St").also { salonRepository.save(it) }

    private fun service(salonId: SalonId, price: String): Service =
        Service.create(salonId, ServiceCategoryId.new(), "Haircut", null, 30, BigDecimal(price)).also { serviceRepository.save(it) }

    private fun booking(salonId: SalonId, serviceId: ServiceId, customerId: UserId, startTime: LocalDateTime, status: BookingStatus): Booking =
        Booking.reconstitute(
            id = BookingId.new(),
            salonId = salonId,
            serviceId = serviceId,
            specialistId = SpecialistId.new(),
            customerId = customerId,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = status,
            notes = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        ).also { bookingRepository.save(it) }

    @Test
    fun `throws when caller owns no salon`() {
        assertThrows(SalonNotFoundException::class.java) {
            useCase.execute(GetDashboardInsightsCommand(UserId.new()))
        }
    }

    @Test
    fun `throws when caller owns more than one salon`() {
        val ownerId = UserId.new()
        salonOwnedBy(ownerId)
        salonOwnedBy(ownerId)

        assertThrows(AmbiguousSalonContextException::class.java) {
            useCase.execute(GetDashboardInsightsCommand(ownerId))
        }
    }

    @Test
    fun `a salon with no bookings returns an all-zero, empty-state response`() {
        val ownerId = UserId.new()
        salonOwnedBy(ownerId)

        val insights = useCase.execute(GetDashboardInsightsCommand(ownerId))

        assertEquals(BigDecimal.ZERO, insights.revenue.today)
        assertEquals(BigDecimal.ZERO, insights.revenue.month)
        assertEquals(BigDecimal.ZERO, insights.revenue.growthRate)
        assertEquals(0L, insights.bookings.total)
        assertEquals(0, insights.customers.newCustomers)
        assertTrue(insights.services.isEmpty())
        assertTrue(insights.recommendations.isEmpty())
    }

    @Test
    fun `computes revenue, booking counts, customers and per-service breakdown for the current month`() {
        val ownerId = UserId.new()
        val salon = salonOwnedBy(ownerId)
        val haircut = service(salon.id, "100.00")

        val today = LocalDate.now()
        val thisMonth = today.withDayOfMonth(1).atTime(10, 0)
        val lastMonth = today.withDayOfMonth(1).minusMonths(1).atTime(10, 0)

        val returningCustomer = UserId.new()
        val newCustomer = UserId.new()

        // Returning customer booked (and completed) last month too.
        booking(salon.id, haircut.id, returningCustomer, lastMonth, BookingStatus.COMPLETED)

        // This month: two completed (revenue), one cancelled, one still pending — same two customers throughout.
        booking(salon.id, haircut.id, returningCustomer, thisMonth, BookingStatus.COMPLETED)
        booking(salon.id, haircut.id, newCustomer, thisMonth.plusHours(1), BookingStatus.COMPLETED)
        booking(salon.id, haircut.id, newCustomer, thisMonth.plusHours(2), BookingStatus.CANCELLED)
        booking(salon.id, haircut.id, returningCustomer, thisMonth.plusHours(3), BookingStatus.PENDING)

        val insights = useCase.execute(GetDashboardInsightsCommand(ownerId))

        assertEquals(4L, insights.bookings.total)
        assertEquals(2L, insights.bookings.completed)
        assertEquals(1L, insights.bookings.cancelled)
        assertEquals(BigDecimal("200.00"), insights.revenue.month)
        assertEquals(1, insights.customers.newCustomers)
        assertEquals(1, insights.customers.returningCustomers)
        assertEquals(1, insights.services.size)
        assertEquals("Haircut", insights.services.single().name)
        assertEquals(2L, insights.services.single().bookings)
        assertEquals(BigDecimal("200.00"), insights.services.single().revenue)
        // Month revenue (200) vs. previous month (100) is a 100% increase.
        assertTrue(insights.revenue.growthRate.signum() > 0)
        assertTrue(insights.recommendations.any { it.type == RecommendationType.REVENUE_GROWTH })
    }

    @Test
    fun `today's revenue only counts bookings starting today`() {
        val ownerId = UserId.new()
        val salon = salonOwnedBy(ownerId)
        val haircut = service(salon.id, "50.00")

        val startOfToday = LocalDate.now().atTime(9, 0)
        val earlierThisMonth = LocalDate.now().withDayOfMonth(1).atTime(9, 0)

        booking(salon.id, haircut.id, UserId.new(), startOfToday, BookingStatus.COMPLETED)
        if (earlierThisMonth.toLocalDate() != LocalDate.now()) {
            booking(salon.id, haircut.id, UserId.new(), earlierThisMonth, BookingStatus.COMPLETED)
        }

        val insights = useCase.execute(GetDashboardInsightsCommand(ownerId))

        assertEquals(BigDecimal("50.00"), insights.revenue.today)
    }

    @Test
    fun `owner can access dashboard via an explicit salonId too`() {
        val ownerId = UserId.new()
        val salon = salonOwnedBy(ownerId)

        val insights = useCase.execute(GetDashboardInsightsCommand(ownerId, salon.id))

        assertTrue(insights.services.isEmpty())
    }

    @Test
    fun `a MANAGER member can access dashboard for the salon they're a member of`() {
        val ownerId = UserId.new()
        val salon = salonOwnedBy(ownerId)
        val managerId = UserId.new()
        membershipRepository.assign(salon.id, managerId, SalonRole.MANAGER)

        val insights = useCase.execute(GetDashboardInsightsCommand(managerId, salon.id))

        assertTrue(insights.services.isEmpty())
    }

    @Test
    fun `a RECEPTIONIST member is denied - dashboard needs VIEW_CRM, which RECEPTIONIST doesn't have`() {
        val ownerId = UserId.new()
        val salon = salonOwnedBy(ownerId)
        val receptionistId = UserId.new()
        membershipRepository.assign(salon.id, receptionistId, SalonRole.RECEPTIONIST)

        assertThrows(SalonAccessDeniedException::class.java) {
            useCase.execute(GetDashboardInsightsCommand(receptionistId, salon.id))
        }
    }

    @Test
    fun `a caller with no relationship to the salon is denied, not silently let through`() {
        val ownerId = UserId.new()
        val salon = salonOwnedBy(ownerId)
        val stranger = UserId.new()

        assertThrows(SalonAccessDeniedException::class.java) {
            useCase.execute(GetDashboardInsightsCommand(stranger, salon.id))
        }
    }
}
