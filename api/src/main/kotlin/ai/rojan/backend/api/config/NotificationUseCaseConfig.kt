package ai.rojan.backend.api.config

import ai.rojan.backend.application.notification.GetSalonNotificationsUseCase
import ai.rojan.backend.application.notification.GetSalonUnreadNotificationCountUseCase
import ai.rojan.backend.application.notification.MarkAllNotificationsReadUseCase
import ai.rojan.backend.application.notification.MarkNotificationReadUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.notification.NotificationRepository
import ai.rojan.backend.domain.salon.SalonRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class NotificationUseCaseConfig {

    @Bean
    fun getSalonNotificationsUseCase(
        salonRepository: SalonRepository,
        salonPermissionResolver: SalonPermissionResolver,
        notificationRepository: NotificationRepository,
    ) = GetSalonNotificationsUseCase(salonRepository, salonPermissionResolver, notificationRepository)

    @Bean
    fun getSalonUnreadNotificationCountUseCase(
        salonRepository: SalonRepository,
        salonPermissionResolver: SalonPermissionResolver,
        notificationRepository: NotificationRepository,
    ) = GetSalonUnreadNotificationCountUseCase(salonRepository, salonPermissionResolver, notificationRepository)

    @Bean
    fun markNotificationReadUseCase(
        salonRepository: SalonRepository,
        salonPermissionResolver: SalonPermissionResolver,
        notificationRepository: NotificationRepository,
    ) = MarkNotificationReadUseCase(salonRepository, salonPermissionResolver, notificationRepository)

    @Bean
    fun markAllNotificationsReadUseCase(
        salonRepository: SalonRepository,
        salonPermissionResolver: SalonPermissionResolver,
        notificationRepository: NotificationRepository,
    ) = MarkAllNotificationsReadUseCase(salonRepository, salonPermissionResolver, notificationRepository)
}
