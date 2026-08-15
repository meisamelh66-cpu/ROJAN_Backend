package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonInviteNotFoundException
import ai.rojan.backend.domain.salon.SalonInviteId
import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

private class NoOpRateLimiter : ai.rojan.backend.application.port.RateLimiterPort {
    override fun tryConsume(key: String, limit: Int, window: Duration): Boolean = true
}

class SalonInviteUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val salonInviteRepository = InMemorySalonInviteRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val createUseCase = CreateSalonInviteUseCase(salonRepository, salonInviteRepository, salonPermissionResolver, Duration.ofHours(1))
    private val listUseCase = ListSalonInvitesUseCase(salonRepository, salonInviteRepository, salonPermissionResolver)
    private val revokeUseCase = RevokeSalonInviteUseCase(salonRepository, salonInviteRepository, salonPermissionResolver)
    private val getUseCase = GetSalonInviteUseCase(salonRepository, salonInviteRepository)
    private val acceptUseCase = AcceptSalonInviteUseCase(salonInviteRepository, membershipRepository, NoOpRateLimiter())

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", "Full service salon", "+1 555 0100", "hello@glow.example", "1 Main St"),
    )

    @Test
    fun `owner can create an invite`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.RECEPTIONIST))

        assertEquals(salon.id, invite.salonId)
        assertEquals(SalonRole.RECEPTIONIST, invite.role)
        assertEquals(SalonInviteStatus.CREATED, invite.currentStatus())
        assertTrue(invite.token.isNotBlank())
    }

    @Test
    fun `a non-owner cannot create an invite`() {
        assertThrows<SalonAccessDeniedException> {
            createUseCase.execute(CreateSalonInviteCommand(salon.id, stranger, SalonRole.RECEPTIONIST))
        }
    }

    @Test
    fun `owner can list invites for their salon`() {
        createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.RECEPTIONIST))
        createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.MANAGER))

        val invites = listUseCase.execute(ListSalonInvitesCommand(salon.id, owner))

        assertEquals(2, invites.size)
    }

    @Test
    fun `owner can revoke a pending invite`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.RECEPTIONIST))

        revokeUseCase.execute(RevokeSalonInviteCommand(salon.id, invite.id, owner))

        assertEquals(SalonInviteStatus.REVOKED, salonInviteRepository.findById(invite.id)!!.currentStatus())
    }

    @Test
    fun `revoking an unknown invite fails`() {
        assertThrows<SalonInviteNotFoundException> {
            revokeUseCase.execute(RevokeSalonInviteCommand(salon.id, SalonInviteId.new(), owner))
        }
    }

    @Test
    fun `accepting a reception invite grants exactly the RECEPTIONIST role`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.RECEPTIONIST))
        val staff = UserId.new()

        val accepted = acceptUseCase.execute(AcceptSalonInviteCommand(invite.token, staff))

        assertEquals(SalonInviteStatus.ACCEPTED, accepted.currentStatus())
        assertEquals(SalonRole.RECEPTIONIST, membershipRepository.findBySalonIdAndUserId(salon.id, staff)!!.role)
    }

    @Test
    fun `accepting a manager invite grants exactly the MANAGER role`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.MANAGER))
        val staff = UserId.new()

        acceptUseCase.execute(AcceptSalonInviteCommand(invite.token, staff))

        assertEquals(SalonRole.MANAGER, membershipRepository.findBySalonIdAndUserId(salon.id, staff)!!.role)
    }

    @Test
    fun `an invalid token is rejected`() {
        assertThrows<SalonInviteNotFoundException> {
            acceptUseCase.execute(AcceptSalonInviteCommand("does-not-exist", UserId.new()))
        }
    }

    @Test
    fun `an expired token is rejected`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.RECEPTIONIST))
        // Simulate expiry by looking the invite up "in the future".
        val farFuture = invite.expiresAt.plusSeconds(1)

        assertNull(salonInviteRepository.acceptIfAvailable(invite.token, UserId.new(), farFuture))
    }

    @Test
    fun `double accept of the same token is rejected`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.RECEPTIONIST))
        acceptUseCase.execute(AcceptSalonInviteCommand(invite.token, UserId.new()))

        assertThrows<SalonInviteNotFoundException> {
            acceptUseCase.execute(AcceptSalonInviteCommand(invite.token, UserId.new()))
        }
    }

    @Test
    fun `a revoked token cannot be accepted`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.RECEPTIONIST))
        revokeUseCase.execute(RevokeSalonInviteCommand(salon.id, invite.id, owner))

        assertThrows<SalonInviteNotFoundException> {
            acceptUseCase.execute(AcceptSalonInviteCommand(invite.token, UserId.new()))
        }
    }

    @Test
    fun `get resolves a valid token to the salon name and role`() {
        val invite = createUseCase.execute(CreateSalonInviteCommand(salon.id, owner, SalonRole.MANAGER))

        val details = getUseCase.execute(GetSalonInviteCommand(invite.token))

        assertEquals(salon.name, details.salonName)
        assertEquals(SalonRole.MANAGER, details.role)
    }

    @Test
    fun `get rejects an invalid token the same way as an expired or revoked one`() {
        assertThrows<SalonInviteNotFoundException> {
            getUseCase.execute(GetSalonInviteCommand("does-not-exist"))
        }
    }
}
