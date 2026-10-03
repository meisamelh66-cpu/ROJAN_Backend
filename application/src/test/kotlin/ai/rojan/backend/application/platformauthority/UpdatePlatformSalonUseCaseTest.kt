package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.audit.InMemoryAuditEventRepository
import ai.rojan.backend.application.audit.RecordAuditEventUseCase
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.audit.ActorType
import ai.rojan.backend.domain.audit.AuditActionType
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Platform Management API Contract (Salons) - Admin Salon Edit: proves [UpdatePlatformSalonUseCase]
 * (1) is PLATFORM_ADMIN only, (2) really persists every field group it accepts - identity/contact
 * ([Salon.update]), location ([Salon.updateProfile]) and business-completeness
 * ([Salon.updateCompletionProfile]) - in one call, and (3) records a SALON_UPDATED audit event with
 * [ActorType.PLATFORM_AUTHORITY], distinguishing a platform edit from an owner's own self-service
 * change (which is also SALON_UPDATED, but [ActorType.OWNER] - this use case is the only caller
 * that can ever record the PLATFORM_AUTHORITY variant).
 */
class UpdatePlatformSalonUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val salonRepository = InMemorySalonRepository()
    private val auditEventRepository = InMemoryAuditEventRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val useCase = UpdatePlatformSalonUseCase(salonRepository, platformAuthorization, RecordAuditEventUseCase(auditEventRepository))

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555042${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Platform Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val reviewer = User.registerWithPhone(nextPhone(), "Platform Reviewer", UserRole.PLATFORM_REVIEWER).also { userRepository.save(it) }
    private val manager = User.registerWithPhone(nextPhone(), "Salon Manager", UserRole.MANAGER).also { userRepository.save(it) }

    private fun salon(): Salon =
        Salon.create(
            ownerId = manager.id,
            name = "Old Name",
            description = "Old description",
            phone = "+15550100",
            email = null,
            address = "Old address",
            onboardingStatus = SalonOnboardingStatus.ACTIVE,
        ).also { salonRepository.save(it) }

    private fun fullCommand(salonId: SalonId, callerId: ai.rojan.backend.domain.user.UserId = admin.id) = UpdatePlatformSalonCommand(
        callerId = callerId,
        salonId = salonId,
        name = "New Name",
        description = "New description",
        phone = "+15559999",
        email = "new@example.com",
        address = "New address",
        latitude = 35.7,
        longitude = 51.3,
        city = "Tehran",
        activityStartJalaliYear = 1395,
        hasInternalExtensions = true,
        sellsProducts = true,
        hasCafe = false,
        hasStaffUniform = true,
        isNeighborhoodSalon = false,
        isCityCenterSalon = true,
    )

    @Test
    fun `PLATFORM_ADMIN can edit a salon's identity, location and business-profile fields in one call`() {
        val target = salon()

        val result = useCase.execute(fullCommand(target.id))

        assertEquals("New Name", result.name)
        assertEquals("New description", result.description)
        assertEquals("+15559999", result.phone)
        assertEquals("new@example.com", result.email)
        assertEquals("New address", result.address)
        assertEquals(35.7, result.latitude)
        assertEquals(51.3, result.longitude)
        assertEquals("Tehran", result.city)
        assertEquals(1395, result.activityStartJalaliYear)
        assertTrue(result.hasInternalExtensions)
        assertEquals(true, result.sellsProducts)
        assertEquals(false, result.hasCafe)

        val persisted = salonRepository.findById(target.id)!!
        assertEquals("New Name", persisted.name)
        assertEquals("Tehran", persisted.city)
    }

    @Test
    fun `PLATFORM_REVIEWER cannot edit a salon - read-only role`() {
        val target = salon()

        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(fullCommand(target.id, callerId = reviewer.id))
        }
        assertEquals("Old Name", salonRepository.findById(target.id)!!.name, "a denied call must never partially apply")
    }

    @Test
    fun `a MANAGER - even this salon's own owner - cannot edit it through the platform route`() {
        val target = salon()

        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(fullCommand(target.id, callerId = manager.id))
        }
    }

    @Test
    fun `editing an unknown salon id throws SalonNotFoundException`() {
        assertThrows<SalonNotFoundException> {
            useCase.execute(fullCommand(SalonId.new()))
        }
    }

    @Test
    fun `a blank name is rejected, same validation Salon_update already enforces`() {
        val target = salon()

        assertThrows<IllegalArgumentException> {
            useCase.execute(fullCommand(target.id).copy(name = "   "))
        }
    }

    @Test
    fun `a successful edit is recorded as a SALON_UPDATED audit event with PLATFORM_AUTHORITY actor type`() {
        val target = salon()

        useCase.execute(fullCommand(target.id))

        val event = auditEventRepository.findBySalonId(target.id).single()
        assertEquals(AuditActionType.SALON_UPDATED, event.actionType)
        assertEquals(ActorType.PLATFORM_AUTHORITY, event.actorType)
        assertEquals(admin.id, event.actorId)
    }
}
