package ai.rojan.backend.api.notification

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.notification.GetSalonNotificationsUseCase
import ai.rojan.backend.application.notification.GetSalonUnreadNotificationCountUseCase
import ai.rojan.backend.application.notification.MarkAllNotificationsReadUseCase
import ai.rojan.backend.application.notification.MarkNotificationReadUseCase
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.notification.NotificationId
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons/{salonId}/notifications")
@Tag(name = "Notifications")
class SalonNotificationController(
    private val currentUserResolver: CurrentUserResolver,
    private val getSalonNotificationsUseCase: GetSalonNotificationsUseCase,
    private val getSalonUnreadNotificationCountUseCase: GetSalonUnreadNotificationCountUseCase,
    private val markNotificationReadUseCase: MarkNotificationReadUseCase,
    private val markAllNotificationsReadUseCase: MarkAllNotificationsReadUseCase,
) {

    @GetMapping
    @Operation(summary = "List notifications for a salon (owner, manager, or receptionist)")
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(required = false) category: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<NotificationResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val result = getSalonNotificationsUseCase.execute(
            SalonId(salonId),
            callerId,
            PageRequest(page, size),
            unreadOnly,
            category,
        )
        return result.toPagedResponse { it.toResponse() }
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get unread notifications count for a salon")
    fun unreadCount(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): UnreadCountResponse {
        val callerId = currentUserResolver.resolve(principal)
        val count = getSalonUnreadNotificationCountUseCase.execute(SalonId(salonId), callerId)
        return UnreadCountResponse(salonId, count)
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "Mark a notification as read")
    fun markRead(
        @PathVariable salonId: UUID,
        @PathVariable notificationId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): NotificationResponse {
        val callerId = currentUserResolver.resolve(principal)
        val updated = markNotificationReadUseCase.execute(
            SalonId(salonId),
            NotificationId(notificationId),
            callerId,
        )
        return updated.toResponse()
    }

    @PostMapping("/mark-all-read")
    @Operation(summary = "Mark all unread notifications for a salon as read")
    fun markAllRead(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): MarkAllReadResponse {
        val callerId = currentUserResolver.resolve(principal)
        val count = markAllNotificationsReadUseCase.execute(SalonId(salonId), callerId)
        return MarkAllReadResponse(salonId, count)
    }
}
