package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerAccessDeniedException
import ai.rojan.backend.domain.common.CustomerTagNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerTagId
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Covers `AddCustomerNoteUseCase`/`AddCustomerTagUseCase`/`RemoveCustomerTagUseCase` together - same fixture, small enough not to warrant three separate files. */
class CustomerNotesAndTagsUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val customerNoteRepository = InMemoryCustomerNoteRepository()
    private val customerTagRepository = InMemoryCustomerTagRepository()
    private val customerActivityRepository = InMemoryCustomerActivityRepository()

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }
    private val customer = Customer.create(salon.id, null, "Jane Doe", PhoneNumber("+989123456789"), null, null)
        .also { customerRepository.save(it) }

    @Test
    fun `AddCustomerNoteUseCase saves the note authored by the caller`() {
        val useCase = AddCustomerNoteUseCase(salonRepository, customerRepository, customerNoteRepository)

        val note = useCase.execute(AddCustomerNoteCommand(customer.id, ownerId, "Prefers morning appointments"))

        assertEquals("Prefers morning appointments", note.text)
        assertEquals(ownerId, note.authorId)
        assertEquals(1, customerNoteRepository.findByCustomerId(customer.id).size)
    }

    @Test
    fun `AddCustomerNoteUseCase rejects a caller who does not own the salon`() {
        val useCase = AddCustomerNoteUseCase(salonRepository, customerRepository, customerNoteRepository)

        assertThrows<CustomerAccessDeniedException> {
            useCase.execute(AddCustomerNoteCommand(customer.id, UserId.new(), "text"))
        }
    }

    @Test
    fun `AddCustomerTagUseCase saves the tag and records a TAG_ADDED activity`() {
        val useCase = AddCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)

        val tag = useCase.execute(AddCustomerTagCommand(customer.id, ownerId, "VIP"))

        assertEquals("VIP", tag.label)
        val activities = customerActivityRepository.findByCustomerId(customer.id)
        assertTrue(activities.any { it.type == CustomerActivityType.TAG_ADDED && it.description.contains("VIP") })
    }

    @Test
    fun `RemoveCustomerTagUseCase deletes the tag and records a TAG_REMOVED activity`() {
        val addUseCase = AddCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)
        val removeUseCase = RemoveCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)
        val tag = addUseCase.execute(AddCustomerTagCommand(customer.id, ownerId, "VIP"))

        removeUseCase.execute(RemoveCustomerTagCommand(customer.id, tag.id, ownerId))

        assertTrue(customerTagRepository.findByCustomerId(customer.id).isEmpty())
        val activities = customerActivityRepository.findByCustomerId(customer.id)
        assertTrue(activities.any { it.type == CustomerActivityType.TAG_REMOVED })
    }

    @Test
    fun `RemoveCustomerTagUseCase rejects a tag that does not belong to this customer`() {
        val otherCustomer = Customer.create(salon.id, null, "Other Person", PhoneNumber("+989999999999"), null, null)
            .also { customerRepository.save(it) }
        val addUseCase = AddCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)
        val removeUseCase = RemoveCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)
        val tagOnOtherCustomer = addUseCase.execute(AddCustomerTagCommand(otherCustomer.id, ownerId, "VIP"))

        assertThrows<CustomerTagNotFoundException> {
            removeUseCase.execute(RemoveCustomerTagCommand(customer.id, tagOnOtherCustomer.id, ownerId))
        }
    }

    @Test
    fun `RemoveCustomerTagUseCase rejects an unknown tag id`() {
        val useCase = RemoveCustomerTagUseCase(salonRepository, customerRepository, customerTagRepository, customerActivityRepository)

        assertThrows<CustomerTagNotFoundException> {
            useCase.execute(RemoveCustomerTagCommand(customer.id, CustomerTagId.new(), ownerId))
        }
    }
}
