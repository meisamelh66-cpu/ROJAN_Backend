package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole

fun isPlatformAdmin(user: User): Boolean = user.role == UserRole.PLATFORM_ADMIN

fun isPlatformReviewerOrAdmin(user: User): Boolean =
    user.role == UserRole.PLATFORM_REVIEWER || user.role == UserRole.PLATFORM_ADMIN

/**
 * The single place every platform-scoped use case resolves and checks the
 * caller's platform authority - deliberately never
 * [ai.rojan.backend.application.salon.SalonPermissionResolver], salon
 * membership, or a [ai.rojan.backend.domain.salon.SalonRole] (platform roles
 * are never salon-scoped, confirmed decision). Mirrors
 * [ai.rojan.backend.application.salon.SalonPermissionResolver]'s own
 * "resolve, then require" shape for the sibling salon-scoped authority
 * model, without routing through it - the two are intentionally separate,
 * unrelated authority sources.
 */
class PlatformAuthorizationResolver(private val userRepository: UserRepository) {

    fun requirePlatformAdmin(callerId: UserId): User {
        val caller = userRepository.findById(callerId) ?: throw PlatformAccessDeniedException(callerId.value.toString())
        if (!isPlatformAdmin(caller)) throw PlatformAccessDeniedException(callerId.value.toString())
        return caller
    }

    fun requirePlatformReviewerOrAdmin(callerId: UserId): User {
        val caller = userRepository.findById(callerId) ?: throw PlatformAccessDeniedException(callerId.value.toString())
        if (!isPlatformReviewerOrAdmin(caller)) throw PlatformAccessDeniedException(callerId.value.toString())
        return caller
    }
}
