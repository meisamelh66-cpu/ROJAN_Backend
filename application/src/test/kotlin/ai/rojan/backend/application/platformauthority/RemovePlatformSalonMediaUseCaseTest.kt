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
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Platform Management API Contract (Salons) - Admin Media Review (Phase 2B "remove an invalid
 * image"): proves [RemovePlatformSalonMediaUseCase] clears only the requested slot ([IdentitySlot.LOGO]
 * or [IdentitySlot.COVER]), leaves the other slot untouched, is PLATFORM_ADMIN only, and records a
 * MEDIA_DELETED audit event only when a media id was actually set (clearing an already-empty slot
 * is a safe no-op that must never fabricate a removal event).
 */
class RemovePlatformSalonMediaUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val salonRepository = InMemorySalonRepository()
    private val auditEventRepository = InMemoryAuditEventRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val useCase = RemovePlatformSalonMediaUseCase(salonRepository, platformAuthorization, RecordAuditEventUseCase(auditEventRepository))

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555043${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Platform Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val reviewer = User.registerWithPhone(nextPhone(), "Platform Reviewer", UserRole.PLATFORM_REVIEWER).also { userRepository.save(it) }
    private val manager = User.registerWithPhone(nextPhone(), "Salon Manager", UserRole.MANAGER).also { userRepository.save(it) }

    private fun salonWithMedia(): Salon =
        Salon.create(
            ownerId = manager.id,
            name = "Salon With Media",
            description = null,
            phone = "+15550100",
            email = null,
            address = "1 Main St",
            onboardingStatus = SalonOnboardingStatus.ACTIVE,
            logoMediaId = MediaAssetId.new(),
            coverMediaId = MediaAssetId.new(),
        ).also { salonRepository.save(it) }

    @Test
    fun `PLATFORM_ADMIN can clear an invalid logo without touching the cover`() {
        val target = salonWithMedia()
        val coverBefore = target.coverMediaId

        val result = useCase.execute(RemovePlatformSalonMediaCommand(admin.id, target.id, IdentitySlot.LOGO))

        assertNull(result.logoMediaId)
        assertEquals(coverBefore, result.coverMediaId, "clearing the logo must never touch the cover slot")
    }

    @Test
    fun `PLATFORM_ADMIN can clear an invalid cover without touching the logo`() {
        val target = salonWithMedia()
        val logoBefore = target.logoMediaId

        val result = useCase.execute(RemovePlatformSalonMediaCommand(admin.id, target.id, IdentitySlot.COVER))

        assertNull(result.coverMediaId)
        assertEquals(logoBefore, result.logoMediaId)
    }

    @Test
    fun `PLATFORM_REVIEWER cannot remove media - read-only role`() {
        val target = salonWithMedia()

        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(RemovePlatformSalonMediaCommand(reviewer.id, target.id, IdentitySlot.LOGO))
        }
        assertEquals(target.logoMediaId, salonRepository.findById(target.id)!!.logoMediaId)
    }

    @Test
    fun `a MANAGER - even this salon's own owner - cannot remove media through the platform route`() {
        val target = salonWithMedia()

        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(RemovePlatformSalonMediaCommand(manager.id, target.id, IdentitySlot.LOGO))
        }
    }

    @Test
    fun `clearing a real logo is recorded as a MEDIA_DELETED audit event referencing the removed media id`() {
        val target = salonWithMedia()
        val removedId = target.logoMediaId!!

        useCase.execute(RemovePlatformSalonMediaCommand(admin.id, target.id, IdentitySlot.LOGO))

        val event = auditEventRepository.findBySalonId(target.id).single()
        assertEquals(AuditActionType.MEDIA_DELETED, event.actionType)
        assertEquals(ActorType.PLATFORM_AUTHORITY, event.actorType)
        assertEquals(AuditEntityType.MEDIA_ASSET, event.entityType)
        assertEquals(removedId.value.toString(), event.entityId)
    }

    @Test
    fun `clearing an already-empty slot is a safe no-op and records no audit event`() {
        val target = Salon.create(
            ownerId = manager.id,
            name = "No Media Salon",
            description = null,
            phone = "+15550100",
            email = null,
            address = "1 Main St",
        ).also { salonRepository.save(it) }

        val result = useCase.execute(RemovePlatformSalonMediaCommand(admin.id, target.id, IdentitySlot.LOGO))

        assertNull(result.logoMediaId)
        assertTrue(auditEventRepository.findBySalonId(target.id).isEmpty(), "clearing an already-null slot must never fabricate a removal event")
    }
}
