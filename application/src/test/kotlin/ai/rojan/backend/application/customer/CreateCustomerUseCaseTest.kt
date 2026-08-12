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
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CreateCustomerUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val useCase = CreateCustomerUseCase(salonRepository, customerRepository, salonPermissionResolver)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    @Test
    fun `creates a walk-in customer with no linked account`() {
        val customer = useCase.execute(
            CreateCustomerCommand(salon.id, ownerId, "Jane Doe", "+989123456789", null, null),
        )

        assertNull(customer.userId)
        assertEquals("Jane Doe", customer.fullName)
        assertEquals("+989123456789", customer.phoneNumber?.value)
    }

    @Test
    fun `rejects a caller who does not own the salon`() {
        assertThrows<SalonAccessDeniedException> {
            useCase.execute(CreateCustomerCommand(salon.id, UserId.new(), "Jane Doe", "+989123456789", null, null))
        }
    }

    @Test
    fun `rejects an unknown salon`() {
        assertThrows<SalonNotFoundException> {
            useCase.execute(CreateCustomerCommand(SalonId.new(), ownerId, "Jane Doe", "+989123456789", null, null))
        }
    }

    @Test
    fun `rejects a duplicate phone number within the same salon`() {
        useCase.execute(CreateCustomerCommand(salon.id, ownerId, "Jane Doe", "+989123456789", null, null))

        assertThrows<CustomerAlreadyExistsException> {
            useCase.execute(CreateCustomerCommand(salon.id, ownerId, "Jane Impersonator", "+989123456789", null, null))
        }
    }

    @Test
    fun `allows the same phone number across two different salons`() {
        val otherSalon = Salon.create(ownerId, "Other Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

        useCase.execute(CreateCustomerCommand(salon.id, ownerId, "Jane Doe", "+989123456789", null, null))
        val secondCustomer = useCase.execute(CreateCustomerCommand(otherSalon.id, ownerId, "Jane Doe", "+989123456789", null, null))

        assertEquals("+989123456789", secondCustomer.phoneNumber?.value)
    }
}
