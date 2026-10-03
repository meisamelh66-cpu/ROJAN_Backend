package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.audit.InMemoryAuditEventRepository
import ai.rojan.backend.application.audit.RecordAuditEventUseCase
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.audit.ActorType
import ai.rojan.backend.domain.audit.AuditActionType
import ai.rojan.backend.domain.audit.AuditEntityType
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Platform Management API Contract (Salons) - Admin Salon Suspend/Reinstate: the real route behind
 * the Website's already-written `salonStatusService.suspend`/`reinstate` stub
 * (`lib/admin/backend-gaps.ts`). Proves (1) PLATFORM_ADMIN only, (2) a reason is required to
 * suspend, (3) the salon is never hard-deleted - only `active` flips, same as an owner's own
 * `DELETE /api/v1/salons/{id}`, (4) every write is recorded as a real [AuditEvent] with
 * [ActorType.PLATFORM_AUTHORITY] and the reason in its metadata, and (5) reinstate is the exact
 * reverse.
 */
class SuspendReinstatePlatformSalonUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val salonRepository = InMemorySalonRepository()
    private val auditEventRepository = InMemoryAuditEventRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val recordAuditEventUseCase = RecordAuditEventUseCase(auditEventRepository)
    private val suspendUseCase = SuspendPlatformSalonUseCase(salonRepository, platformAuthorization, recordAuditEventUseCase)
    private val reinstateUseCase = ReinstatePlatformSalonUseCase(salonRepository, platformAuthorization, recordAuditEventUseCase)

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555041${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Platform Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val reviewer = User.registerWithPhone(nextPhone(), "Platform Reviewer", UserRole.PLATFORM_REVIEWER).also { userRepository.save(it) }
    private val manager = User.registerWithPhone(nextPhone(), "Salon Manager", UserRole.MANAGER).also { userRepository.save(it) }

    private fun salon(): Salon =
        Salon.create(
            ownerId = manager.id,
            name = "Test Salon",
            description = null,
            phone = "+15550100",
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.ACTIVE,
        ).also { salonRepository.save(it) }

    @Test
    fun `PLATFORM_ADMIN can suspend a salon, which flips active to false without touching onboardingStatus`() {
        val target = salon()

        val result = suspendUseCase.execute(SuspendPlatformSalonCommand(admin.id, target.id, "تکراری است"))

        assertFalse(result.active)
        assertEquals(SalonOnboardingStatus.ACTIVE, result.onboardingStatus, "suspend must never touch onboardingStatus")
        assertFalse(salonRepository.findById(target.id)!!.active, "the suspension must be persisted")
    }

    @Test
    fun `PLATFORM_REVIEWER cannot suspend a salon - read-only role`() {
        val target = salon()

        assertThrows<PlatformAccessDeniedException> {
            suspendUseCase.execute(SuspendPlatformSalonCommand(reviewer.id, target.id, "a reason"))
        }
        assertTrue(salonRepository.findById(target.id)!!.active, "a denied call must never partially apply")
    }

    @Test
    fun `a MANAGER - even this salon's own owner - cannot suspend it through the platform route`() {
        val target = salon()

        assertThrows<PlatformAccessDeniedException> {
            suspendUseCase.execute(SuspendPlatformSalonCommand(manager.id, target.id, "a reason"))
        }
    }

    @Test
    fun `a blank reason is rejected before any state change`() {
        val target = salon()

        assertThrows<IllegalArgumentException> {
            suspendUseCase.execute(SuspendPlatformSalonCommand(admin.id, target.id, "   "))
        }
        assertTrue(salonRepository.findById(target.id)!!.active)
        assertTrue(auditEventRepository.findBySalonId(target.id).isEmpty(), "a rejected command must never reach the audit log")
    }

    @Test
    fun `suspending an unknown salon id throws SalonNotFoundException`() {
        assertThrows<SalonNotFoundException> {
            suspendUseCase.execute(SuspendPlatformSalonCommand(admin.id, SalonId.new(), "a reason"))
        }
    }

    @Test
    fun `a successful suspend is recorded as a SALON_SUSPENDED audit event with the real actor and reason`() {
        val target = salon()

        suspendUseCase.execute(SuspendPlatformSalonCommand(admin.id, target.id, "سالن آزمایشی/اسپم"))

        val events = auditEventRepository.findBySalonId(target.id)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(AuditActionType.SALON_SUSPENDED, event.actionType)
        assertEquals(ActorType.PLATFORM_AUTHORITY, event.actorType)
        assertEquals(admin.id, event.actorId)
        assertEquals(AuditEntityType.SALON, event.entityType)
        assertEquals(target.id.value.toString(), event.entityId)
        assertTrue(event.metadata?.contains("سالن آزمایشی/اسپم") == true, "the real reason must be recoverable from metadata")
    }

    @Test
    fun `PLATFORM_ADMIN can reinstate a previously suspended salon`() {
        val target = salon()
        suspendUseCase.execute(SuspendPlatformSalonCommand(admin.id, target.id, "a reason"))

        val result = reinstateUseCase.execute(ReinstatePlatformSalonCommand(admin.id, target.id))

        assertTrue(result.active)
        assertTrue(salonRepository.findById(target.id)!!.active)
    }

    @Test
    fun `PLATFORM_REVIEWER cannot reinstate a salon either`() {
        val target = salon()
        suspendUseCase.execute(SuspendPlatformSalonCommand(admin.id, target.id, "a reason"))

        assertThrows<PlatformAccessDeniedException> {
            reinstateUseCase.execute(ReinstatePlatformSalonCommand(reviewer.id, target.id))
        }
    }

    @Test
    fun `a successful reinstate is recorded as a SALON_REINSTATED audit event`() {
        val target = salon()
        suspendUseCase.execute(SuspendPlatformSalonCommand(admin.id, target.id, "a reason"))

        reinstateUseCase.execute(ReinstatePlatformSalonCommand(admin.id, target.id))

        val events = auditEventRepository.findBySalonId(target.id)
        assertEquals(AuditActionType.SALON_REINSTATED, events.last().actionType)
        assertEquals(ActorType.PLATFORM_AUTHORITY, events.last().actorType)
    }

    @Test
    fun `reinstating an already-active salon is idempotent and still succeeds`() {
        val target = salon()

        val result = reinstateUseCase.execute(ReinstatePlatformSalonCommand(admin.id, target.id))

        assertTrue(result.active)
    }
}
