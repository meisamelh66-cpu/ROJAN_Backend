package ai.rojan.backend.application.notification

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.NotificationNotFoundException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.notification.Notification
import ai.rojan.backend.domain.notification.NotificationSeverity
import ai.rojan.backend.domain.notification.NotificationType
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NotificationUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val permissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val notificationRepository = InMemoryNotificationRepository()

    private val getNotificationsUseCase = GetSalonNotificationsUseCase(salonRepository, permissionResolver, notificationRepository)
    private val unreadCountUseCase = GetSalonUnreadNotificationCountUseCase(salonRepository, permissionResolver, notificationRepository)
    private val markReadUseCase = MarkNotificationReadUseCase(salonRepository, permissionResolver, notificationRepository)
    private val markAllReadUseCase = MarkAllNotificationsReadUseCase(salonRepository, permissionResolver, notificationRepository)

    private val owner = UserId.new()
    private val manager = UserId.new()
    private val outsider = UserId.new()
    private val salon: Salon = salonRepository.save(Salon.create(owner, "Beauty Haven", null, "+989123456789", null, "Tehran"))

    init {
        // Activate salon
        salon.activate()
        salonRepository.save(salon)
        // Add manager member
        membershipRepository.assign(salon.id, manager, SalonRole.MANAGER)
    }

    private fun createNotification(
        salonId: SalonId = salon.id,
        title: String = "Test Notification",
        type: NotificationType = NotificationType.BOOKING_CREATED,
        category: String = "bookings",
    ): Notification = notificationRepository.save(
        Notification.create(
            salonId = salonId,
            type = type,
            title = title,
            message = "Details for $title",
            severity = NotificationSeverity.INFO,
            category = category,
        )
    )

    @Test
    fun `owner and manager can list notifications with pagination`() {
        createNotification(title = "Notice 1")
        createNotification(title = "Notice 2")
        createNotification(title = "Notice 3")

        val result = getNotificationsUseCase.execute(salon.id, owner, PageRequest(0, 2))

        assertEquals(2, result.content.size)
        assertEquals(3L, result.totalElements)
        assertEquals(2, result.totalPages)

        // Manager can also list
        val managerResult = getNotificationsUseCase.execute(salon.id, manager, PageRequest(0, 10))
        assertEquals(3, managerResult.content.size)
    }

    @Test
    fun `unreadOnly filter returns only unread notifications`() {
        val n1 = createNotification(title = "Unread Notice")
        val n2 = createNotification(title = "Read Notice")
        markReadUseCase.execute(salon.id, n2.id, owner)

        val unreadList = getNotificationsUseCase.execute(salon.id, owner, PageRequest(0, 10), unreadOnly = true)

        assertEquals(1, unreadList.content.size)
        assertEquals(n1.id, unreadList.content[0].id)
    }

    @Test
    fun `category filter returns only notifications for requested category`() {
        createNotification(category = "bookings")
        createNotification(category = "system")

        val bookingNotifs = getNotificationsUseCase.execute(salon.id, owner, PageRequest(0, 10), category = "bookings")
        assertEquals(1, bookingNotifs.content.size)
        assertEquals("bookings", bookingNotifs.content[0].category)
    }

    @Test
    fun `unread count reflects only unread notifications for the salon`() {
        val n1 = createNotification()
        val n2 = createNotification()
        assertEquals(2L, unreadCountUseCase.execute(salon.id, owner))

        markReadUseCase.execute(salon.id, n1.id, owner)
        assertEquals(1L, unreadCountUseCase.execute(salon.id, manager))
    }

    @Test
    fun `marking a notification as read sets isRead, readAt and readBy`() {
        val n = createNotification()
        assertFalse(n.isRead)

        val updated = markReadUseCase.execute(salon.id, n.id, manager)

        assertTrue(updated.isRead)
        assertEquals(manager, updated.readBy)
        assertNotNull(updated.readAt)
    }

    @Test
    fun `marking all notifications as read updates all unread notifications`() {
        createNotification()
        createNotification()
        assertEquals(2L, unreadCountUseCase.execute(salon.id, owner))

        val updatedCount = markAllReadUseCase.execute(salon.id, owner)
        assertEquals(2, updatedCount)
        assertEquals(0L, unreadCountUseCase.execute(salon.id, owner))
    }

    @Test
    fun `outsider without MANAGE_BOOKINGS permission is rejected with SalonAccessDeniedException`() {
        assertThrows<SalonAccessDeniedException> {
            getNotificationsUseCase.execute(salon.id, outsider, PageRequest(0, 10))
        }

        assertThrows<SalonAccessDeniedException> {
            unreadCountUseCase.execute(salon.id, outsider)
        }
    }

    @Test
    fun `marking notification from another salon throws NotificationNotFoundException to prevent cross-tenant leakage`() {
        val otherSalon = salonRepository.save(Salon.create(UserId.new(), "Other Salon", null, "+989999999999", null, "Isfahan"))
        otherSalon.activate()
        salonRepository.save(otherSalon)

        val otherNotif = createNotification(salonId = otherSalon.id)

        assertThrows<NotificationNotFoundException> {
            markReadUseCase.execute(salon.id, otherNotif.id, owner)
        }
    }
}
