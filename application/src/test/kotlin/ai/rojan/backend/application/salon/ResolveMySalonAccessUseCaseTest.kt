package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResolveMySalonAccessUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val useCase = ResolveMySalonAccessUseCase(salonRepository, membershipRepository, specialistRepository, salonPermissionResolver)

    private fun newSalon(owner: UserId, name: String = "Glow Salon") =
        CreateSalonUseCase(salonRepository).execute(
            CreateSalonCommand(owner, name, "Full service salon", "+1 555 0100", "hello@glow.example", "1 Main St"),
        )

    @Test
    fun `owner sees their salon with every permission`() {
        val owner = UserId.new()
        val salon = newSalon(owner)

        val access = useCase.execute(ResolveMySalonAccessCommand(owner))

        assertEquals(1, access.ownedSalons.size)
        assertEquals(salon.id, access.ownedSalons[0].salon.id)
        assertEquals(Permission.entries.toSet(), access.ownedSalons[0].permissions)
        assertTrue(access.memberships.isEmpty())
        assertTrue(access.specialistLinks.isEmpty())
    }

    @Test
    fun `manager membership resolves MANAGER's real permission set`() {
        val owner = UserId.new()
        val manager = UserId.new()
        val salon = newSalon(owner)
        membershipRepository.assign(salon.id, manager, SalonRole.MANAGER)

        val access = useCase.execute(ResolveMySalonAccessCommand(manager))

        assertEquals(1, access.memberships.size)
        assertEquals(salon.id, access.memberships[0].salon.id)
        assertEquals(SalonRole.MANAGER, access.memberships[0].membership.role)
        assertEquals(SalonRole.MANAGER.permissions(), access.memberships[0].permissions)
        assertTrue(access.ownedSalons.isEmpty())
    }

    @Test
    fun `receptionist membership resolves RECEPTIONIST's real permission set`() {
        val owner = UserId.new()
        val receptionist = UserId.new()
        val salon = newSalon(owner)
        membershipRepository.assign(salon.id, receptionist, SalonRole.RECEPTIONIST)

        val access = useCase.execute(ResolveMySalonAccessCommand(receptionist))

        assertEquals(1, access.memberships.size)
        assertEquals(SalonRole.RECEPTIONIST, access.memberships[0].membership.role)
        assertEquals(SalonRole.RECEPTIONIST.permissions(), access.memberships[0].permissions)
    }

    @Test
    fun `own specialist link resolves MANAGE_SCHEDULE_OWN only`() {
        val owner = UserId.new()
        val specialistUser = UserId.new()
        val salon = newSalon(owner)
        val specialist = ai.rojan.backend.domain.salon.Specialist.create(
            salonId = salon.id,
            userId = specialistUser,
            displayName = "Sara",
            bio = null,
            photoUrl = null,
        )
        specialistRepository.save(specialist)

        val access = useCase.execute(ResolveMySalonAccessCommand(specialistUser))

        assertEquals(1, access.specialistLinks.size)
        assertEquals(salon.id, access.specialistLinks[0].salon.id)
        assertEquals(setOf(Permission.MANAGE_SCHEDULE_OWN), access.specialistLinks[0].permissions)
        assertTrue(access.ownedSalons.isEmpty())
        assertTrue(access.memberships.isEmpty())
    }

    @Test
    fun `tenant isolation - a caller never sees another user's salon relationships`() {
        val owner = UserId.new()
        val manager = UserId.new()
        val stranger = UserId.new()
        val salon = newSalon(owner)
        membershipRepository.assign(salon.id, manager, SalonRole.MANAGER)

        val access = useCase.execute(ResolveMySalonAccessCommand(stranger))

        assertTrue(access.ownedSalons.isEmpty())
        assertTrue(access.memberships.isEmpty())
        assertTrue(access.specialistLinks.isEmpty())
    }

    @Test
    fun `a revoked membership no longer appears`() {
        val owner = UserId.new()
        val manager = UserId.new()
        val salon = newSalon(owner)
        membershipRepository.assign(salon.id, manager, SalonRole.MANAGER)
        assertEquals(1, useCase.execute(ResolveMySalonAccessCommand(manager)).memberships.size)

        membershipRepository.remove(salon.id, manager)

        val access = useCase.execute(ResolveMySalonAccessCommand(manager))
        assertTrue(access.memberships.isEmpty())
    }

    @Test
    fun `a user with owner, manager, and specialist relationships across different salons sees all three`() {
        val user = UserId.new()
        val ownedSalon = newSalon(user, "Owned Salon")
        val otherOwner = UserId.new()
        val managedSalon = newSalon(otherOwner, "Managed Salon")
        val specialistSalon = newSalon(otherOwner, "Specialist Salon")
        membershipRepository.assign(managedSalon.id, user, SalonRole.MANAGER)
        specialistRepository.save(
            ai.rojan.backend.domain.salon.Specialist.create(
                salonId = specialistSalon.id,
                userId = user,
                displayName = "Sara",
                bio = null,
                photoUrl = null,
            ),
        )

        val access = useCase.execute(ResolveMySalonAccessCommand(user))

        assertEquals(1, access.ownedSalons.size)
        assertEquals(ownedSalon.id, access.ownedSalons[0].salon.id)
        assertEquals(1, access.memberships.size)
        assertEquals(managedSalon.id, access.memberships[0].salon.id)
        assertEquals(1, access.specialistLinks.size)
        assertEquals(specialistSalon.id, access.specialistLinks[0].salon.id)
    }

    @Test
    fun `a plain customer with no salon relationships gets three empty lists, not an error`() {
        val customer = UserId.new()

        val access = useCase.execute(ResolveMySalonAccessCommand(customer))

        assertTrue(access.ownedSalons.isEmpty())
        assertTrue(access.memberships.isEmpty())
        assertTrue(access.specialistLinks.isEmpty())
    }
}
