package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.InvalidMembershipAssignmentException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun newSalon(ownerId: UserId = UserId.new()) = Salon.create(
    ownerId = ownerId,
    name = "Glow Salon",
    description = null,
    phone = "+1 555 0100",
    email = null,
    address = "1 Main St",
)

class UpdateSalonCompletionProfileUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)

    private val useCase = UpdateSalonCompletionProfileUseCase(salonRepository, membershipRepository, salonPermissionResolver)
    private val getCompletenessUseCase = GetSalonCompletenessUseCase(
        salonRepository, InMemoryServiceRepository(), specialistRepository,
        ai.rojan.backend.application.schedule.InMemoryWorkingHoursRepository(), salonPermissionResolver,
    )

    private val owner = UserId.new()
    private val salon = newSalon(owner).also { salonRepository.save(it) }

    @Test
    fun `an owner can update the completion profile fields without resubmitting core salon fields`() {
        val membership = membershipRepository.assign(salon.id, UserId.new(), SalonRole.RECEPTIONIST)

        val updated = useCase.execute(
            UpdateSalonCompletionProfileCommand(
                salonId = salon.id,
                callerId = owner,
                activityStartJalaliYear = 1399,
                hasInternalExtensions = true,
                sellsProducts = true,
                hasCafe = false,
                hasStaffUniform = null,
                isNeighborhoodSalon = true,
                isCityCenterSalon = false,
                primaryContactMembershipId = membership.id,
            ),
        )

        assertEquals(1399, updated.activityStartJalaliYear)
        assertEquals(true, updated.hasInternalExtensions)
        assertEquals(true, updated.sellsProducts)
        assertEquals(false, updated.hasCafe)
        assertNull(updated.hasStaffUniform)
        assertEquals("Glow Salon", updated.name) // core fields untouched
    }

    @Test
    fun `an unanswered optional field stays null - never silently converted to false`() {
        val updated = useCase.execute(
            UpdateSalonCompletionProfileCommand(
                salonId = salon.id,
                callerId = owner,
                activityStartJalaliYear = 1399,
                hasInternalExtensions = false,
                sellsProducts = null,
                hasCafe = null,
                hasStaffUniform = null,
                isNeighborhoodSalon = null,
                isCityCenterSalon = null,
                primaryContactMembershipId = null,
            ),
        )

        assertNull(updated.sellsProducts)
        assertNull(updated.hasCafe)
        assertNull(updated.hasStaffUniform)
        assertNull(updated.isNeighborhoodSalon)
        assertNull(updated.isCityCenterSalon)
    }

    @Test
    fun `an invalid (non-positive) Jalali year is rejected`() {
        assertThrows<IllegalArgumentException> {
            useCase.execute(
                UpdateSalonCompletionProfileCommand(
                    salonId = salon.id,
                    callerId = owner,
                    activityStartJalaliYear = 0,
                    hasInternalExtensions = false,
                    sellsProducts = null,
                    hasCafe = null,
                    hasStaffUniform = null,
                    isNeighborhoodSalon = null,
                    isCityCenterSalon = null,
                    primaryContactMembershipId = null,
                ),
            )
        }
    }

    @Test
    fun `a primary contact membership belonging to another salon is rejected`() {
        val otherSalon = newSalon().also { salonRepository.save(it) }
        val foreignMembership = membershipRepository.assign(otherSalon.id, UserId.new(), SalonRole.MANAGER)

        assertThrows<InvalidMembershipAssignmentException> {
            useCase.execute(
                UpdateSalonCompletionProfileCommand(
                    salonId = salon.id,
                    callerId = owner,
                    activityStartJalaliYear = 1399,
                    hasInternalExtensions = false,
                    sellsProducts = null,
                    hasCafe = null,
                    hasStaffUniform = null,
                    isNeighborhoodSalon = null,
                    isCityCenterSalon = null,
                    primaryContactMembershipId = foreignMembership.id,
                ),
            )
        }
    }

    @Test
    fun `updating an unknown salon throws`() {
        assertThrows<SalonNotFoundException> {
            useCase.execute(
                UpdateSalonCompletionProfileCommand(
                    salonId = SalonId.new(),
                    callerId = owner,
                    activityStartJalaliYear = 1399,
                    hasInternalExtensions = false,
                    sellsProducts = null,
                    hasCafe = null,
                    hasStaffUniform = null,
                    isNeighborhoodSalon = null,
                    isCityCenterSalon = null,
                    primaryContactMembershipId = null,
                ),
            )
        }
    }

    @Test
    fun `completeness read reflects what activation still needs, without inventing a percentage`() {
        val result = getCompletenessUseCase.execute(salon.id, owner)

        assertEquals(salon.id, result.salon.id)
        assert(result.missingForActivation.contains("at least one active service"))
        assert(result.missingForActivation.contains("at least one active specialist"))
        assert(result.missingForActivation.contains("at least one configured working-hours day"))
    }

    @Test
    fun `completeness read never lists activity start year or primary contact as missing - they are trackable but non-blocking`() {
        val result = getCompletenessUseCase.execute(salon.id, owner)

        assert(!result.missingForActivation.contains("activity start year"))
        assert(!result.missingForActivation.contains("a designated primary contact"))
    }
}
