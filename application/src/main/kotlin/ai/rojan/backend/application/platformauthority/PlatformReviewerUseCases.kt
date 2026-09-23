package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PhoneNumberAlreadyRegisteredException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole

/**
 * [role] is never a field on this command - a reviewer account is always
 * created as [UserRole.PLATFORM_REVIEWER], assigned server-side by
 * [CreatePlatformReviewerUseCase] itself, never taken from caller input. This
 * is the whole point: a client can never self-select platform authority.
 */
data class CreatePlatformReviewerCommand(
    val callerId: UserId,
    val phoneNumber: PhoneNumber,
    val fullName: String,
)

/**
 * Admin-only. Reuses [User.registerWithPhone] wholesale - the exact same
 * OTP-identity account shape every other phone-based ROJAN account already
 * has, per the approved decision "do not create a separate reviewer
 * authentication mechanism." The created account has no password and
 * authenticates via the existing phone+OTP flow like any other user; nothing
 * about that flow is touched by this use case.
 */
class CreatePlatformReviewerUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: CreatePlatformReviewerCommand): User {
        platformAuthorization.requirePlatformAdmin(command.callerId)

        if (userRepository.existsByPhoneNumber(command.phoneNumber)) {
            throw PhoneNumberAlreadyRegisteredException(command.phoneNumber.value)
        }

        val reviewer = User.registerWithPhone(
            phoneNumber = command.phoneNumber,
            fullName = command.fullName,
            role = UserRole.PLATFORM_REVIEWER,
        )
        return userRepository.save(reviewer)
    }
}

data class ListPlatformReviewersQuery(val callerId: UserId)

class ListPlatformReviewersUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListPlatformReviewersQuery): List<User> {
        platformAuthorization.requirePlatformAdmin(query.callerId)
        return userRepository.findByRole(UserRole.PLATFORM_REVIEWER)
    }
}

data class DeactivatePlatformReviewerCommand(val callerId: UserId, val reviewerId: UserId)

/** Admin-only. [User.deactivate] is idempotent - deactivating an already-inactive reviewer is a harmless no-op, not an error. */
class DeactivatePlatformReviewerUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: DeactivatePlatformReviewerCommand): User {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val reviewer = findReviewer(command.reviewerId, userRepository)
        reviewer.deactivate()
        return userRepository.save(reviewer)
    }
}

data class ReactivatePlatformReviewerCommand(val callerId: UserId, val reviewerId: UserId)

/** Admin-only. Uses [User.reactivate] - the reverse of [DeactivatePlatformReviewerUseCase], same idempotent shape. */
class ReactivatePlatformReviewerUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ReactivatePlatformReviewerCommand): User {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val reviewer = findReviewer(command.reviewerId, userRepository)
        reviewer.reactivate()
        return userRepository.save(reviewer)
    }
}

/** Shared by the deactivate/reactivate use cases above - a target id that exists but isn't actually a [UserRole.PLATFORM_REVIEWER] 404s identically to an unknown id, same "don't distinguish exists-but-wrong-kind from not-found" discipline [ai.rojan.backend.domain.common.SalonInviteNotFoundException]'s own doc comment already documents for a different aggregate. */
private fun findReviewer(reviewerId: UserId, userRepository: UserRepository): User =
    userRepository.findById(reviewerId)?.takeIf { it.role == UserRole.PLATFORM_REVIEWER }
        ?: throw UserNotFoundException(reviewerId.value.toString())
