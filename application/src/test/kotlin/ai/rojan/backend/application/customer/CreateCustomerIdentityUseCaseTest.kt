package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.CustomerAlreadyExistsException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CreateCustomerIdentityUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val useCase = CreateCustomerIdentityUseCase(salonRepository, customerRepository, salonPermissionResolver)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    @Test
    fun `receptionist can register a walk-in customer with identity fields only`() {
        val receptionistId = UserId.new()
        membershipRepository.assign(salon.id, receptionistId, SalonRole.RECEPTIONIST)

        val customer = useCase.execute(
            CreateCustomerIdentityCommand(salon.id, receptionistId, "Jane Doe", "+989123456789", null),
        )

        assertNull(customer.userId)
        assertEquals("Jane Doe", customer.fullName)
        assertEquals("+989123456789", customer.phoneNumber?.value)
    }

    @Test
    fun `owner can also register a walk-in customer through the identity path`() {
        val customer = useCase.execute(
            CreateCustomerIdentityCommand(salon.id, ownerId, "Jane Doe", "+989123456789", null),
        )

        assertEquals("Jane Doe", customer.fullName)
    }

    @Test
    fun `rejects a caller with no salon relationship`() {
        assertThrows<SalonAccessDeniedException> {
            useCase.execute(CreateCustomerIdentityCommand(salon.id, UserId.new(), "Jane Doe", "+989123456789", null))
        }
    }

    @Test
    fun `rejects a specialist link - identity creation is not granted by MANAGE_SCHEDULE_OWN`() {
        val specialistUserId = UserId.new()
        specialistRepository.save(
            ai.rojan.backend.domain.salon.Specialist.create(salon.id, specialistUserId, "Jamie Stylist", null, null),
        )

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(CreateCustomerIdentityCommand(salon.id, specialistUserId, "Jane Doe", "+989123456789", null))
        }
    }

    @Test
    fun `rejects an unknown salon`() {
        assertThrows<SalonNotFoundException> {
            useCase.execute(CreateCustomerIdentityCommand(SalonId.new(), ownerId, "Jane Doe", "+989123456789", null))
        }
    }

    @Test
    fun `rejects a duplicate phone number within the same salon`() {
        useCase.execute(CreateCustomerIdentityCommand(salon.id, ownerId, "Jane Doe", "+989123456789", null))

        assertThrows<CustomerAlreadyExistsException> {
            useCase.execute(CreateCustomerIdentityCommand(salon.id, ownerId, "Jane Impersonator", "+989123456789", null))
        }
    }

    @Test
    fun `never persists a company - the command has no such field to carry one`() {
        val customer = useCase.execute(
            CreateCustomerIdentityCommand(salon.id, ownerId, "Jane Doe", "+989123456789", null),
        )

        assertNull(customer.company)
    }
}
