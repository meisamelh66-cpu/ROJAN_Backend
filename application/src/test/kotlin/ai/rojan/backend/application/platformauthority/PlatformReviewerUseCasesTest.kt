package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PhoneNumberAlreadyRegisteredException
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlatformReviewerUseCasesTest {

    private val userRepository = InMemorySalonUserRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)

    private val createUseCase = CreatePlatformReviewerUseCase(userRepository, platformAuthorization)
    private val listUseCase = ListPlatformReviewersUseCase(userRepository, platformAuthorization)
    private val deactivateUseCase = DeactivatePlatformReviewerUseCase(userRepository, platformAuthorization)
    private val reactivateUseCase = ReactivatePlatformReviewerUseCase(userRepository, platformAuthorization)

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555030${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Admin One", UserRole.PLATFORM_ADMIN)
        .also { userRepository.save(it) }
    private val existingReviewer = User.registerWithPhone(nextPhone(), "Reviewer Zero", UserRole.PLATFORM_REVIEWER)
        .also { userRepository.save(it) }
    private val strangerId = UserId.new().also {
        userRepository.save(User.registerWithPhone(nextPhone(), "Just a Customer", UserRole.CUSTOMER))
    }

    // ---------- create: admin-only, role always server-assigned ----------

    @Test
    fun `an admin can create a reviewer account with PLATFORM_REVIEWER assigned server-side`() {
        val phone = nextPhone()
        val created = createUseCase.execute(CreatePlatformReviewerCommand(admin.id, phone, "New Reviewer"))

        assertEquals(UserRole.PLATFORM_REVIEWER, created.role)
        assertEquals(phone, created.phoneNumber)
        assertTrue(created.active)
    }

    @Test
    fun `a reviewer cannot create another reviewer account`() {
        assertThrows<PlatformAccessDeniedException> {
            createUseCase.execute(CreatePlatformReviewerCommand(existingReviewer.id, nextPhone(), "Attempted Reviewer"))
        }
    }

    @Test
    fun `a non-platform caller cannot create a reviewer account`() {
        assertThrows<PlatformAccessDeniedException> {
            createUseCase.execute(CreatePlatformReviewerCommand(strangerId, nextPhone(), "Attempted Reviewer"))
        }
    }

    @Test
    fun `creating a reviewer with an already-registered phone number throws`() {
        assertThrows<PhoneNumberAlreadyRegisteredException> {
            createUseCase.execute(CreatePlatformReviewerCommand(admin.id, existingReviewer.phoneNumber!!, "Duplicate"))
        }
    }

    // ---------- list: admin-only ----------

    @Test
    fun `an admin can list every reviewer account`() {
        val reviewers = listUseCase.execute(ListPlatformReviewersQuery(admin.id))
        assertTrue(reviewers.any { it.id == existingReviewer.id })
    }

    @Test
    fun `a reviewer cannot list reviewer accounts - reviewers may never manage reviewers`() {
        assertThrows<PlatformAccessDeniedException> {
            listUseCase.execute(ListPlatformReviewersQuery(existingReviewer.id))
        }
    }

    // ---------- deactivate / reactivate: admin-only, idempotent ----------

    @Test
    fun `an admin can deactivate and then reactivate a reviewer account`() {
        deactivateUseCase.execute(DeactivatePlatformReviewerCommand(admin.id, existingReviewer.id))
        assertFalse(userRepository.findById(existingReviewer.id)!!.active)

        reactivateUseCase.execute(ReactivatePlatformReviewerCommand(admin.id, existingReviewer.id))
        assertTrue(userRepository.findById(existingReviewer.id)!!.active)
    }

    @Test
    fun `a reviewer cannot deactivate another reviewer account`() {
        assertThrows<PlatformAccessDeniedException> {
            deactivateUseCase.execute(DeactivatePlatformReviewerCommand(existingReviewer.id, existingReviewer.id))
        }
    }

    @Test
    fun `deactivating an unknown reviewer id throws`() {
        assertThrows<UserNotFoundException> {
            deactivateUseCase.execute(DeactivatePlatformReviewerCommand(admin.id, UserId.new()))
        }
    }

    @Test
    fun `deactivating a non-reviewer account, for example a customer, 404s the same as an unknown id`() {
        assertThrows<UserNotFoundException> {
            deactivateUseCase.execute(DeactivatePlatformReviewerCommand(admin.id, strangerId))
        }
    }
}
