package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.domain.common.CustomerAccessDeniedException
import ai.rojan.backend.domain.common.InvalidCustomerStateException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerStatus
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UpdateCustomerUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val customerActivityRepository = InMemoryCustomerActivityRepository()
    private val useCase = UpdateCustomerUseCase(salonRepository, customerRepository, customerActivityRepository)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }
    private val customer = Customer.create(salon.id, null, "Jane Doe", ai.rojan.backend.domain.auth.PhoneNumber("+989123456789"), null, null)
        .also { customerRepository.save(it) }

    @Test
    fun `updates only the supplied profile fields, leaving the rest unchanged`() {
        val updated = useCase.execute(
            UpdateCustomerCommand(customer.id, ownerId, fullName = "Jane Smith", phoneNumber = null, email = null, company = null, status = null),
        )

        assertEquals("Jane Smith", updated.fullName)
        assertEquals("+989123456789", updated.phoneNumber?.value) // untouched - PATCH semantics
    }

    @Test
    fun `a valid status change is applied and recorded as an activity`() {
        val updated = useCase.execute(
            UpdateCustomerCommand(customer.id, ownerId, null, null, null, null, status = CustomerStatus.PROSPECT),
        )

        assertEquals(CustomerStatus.PROSPECT, updated.status)
        val activities = customerActivityRepository.findByCustomerId(customer.id)
        assertEquals(1, activities.size)
        assertEquals(ai.rojan.backend.domain.customer.CustomerActivityType.STATUS_CHANGED, activities[0].type)
    }

    @Test
    fun `an illegal status jump is rejected and nothing is recorded`() {
        assertThrows<InvalidCustomerStateException> {
            useCase.execute(UpdateCustomerCommand(customer.id, ownerId, null, null, null, null, status = CustomerStatus.VIP))
        }
        assertEquals(0, customerActivityRepository.findByCustomerId(customer.id).size)
    }

    @Test
    fun `rejects a caller who does not own the salon`() {
        assertThrows<CustomerAccessDeniedException> {
            useCase.execute(UpdateCustomerCommand(customer.id, UserId.new(), "Someone Else", null, null, null, null))
        }
    }
}
