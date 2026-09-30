package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerAlreadyLinkedException
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.UserAlreadyLinkedToCustomerException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LinkCustomerToUserUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val customerActivityRepository = InMemoryCustomerActivityRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val useCase = LinkCustomerToUserUseCase(salonRepository, customerRepository, userRepository, customerActivityRepository, salonPermissionResolver)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    private fun newWalkInCustomer(phone: String = "+989120000001") =
        Customer.create(salon.id, null, "Jane Doe", PhoneNumber(phone), null, null).also { customerRepository.save(it) }

    private fun newActiveUser(email: String = "ada@example.com") =
        User.register(Email(email), "hash", "Ada Lovelace", UserRole.CUSTOMER).also { userRepository.register(it) }

    @Test
    fun `links an unlinked walk-in customer to an existing user and records an activity`() {
        val customer = newWalkInCustomer()
        val user = newActiveUser()

        val linked = useCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, user.id))

        assertEquals(user.id, linked.userId)
        val activities = customerActivityRepository.findByCustomerId(customer.id)
        assertEquals(1, activities.size)
        assertEquals(CustomerActivityType.USER_LINKED, activities[0].type)
    }

    @Test
    fun `persists the linked userId and leaves every other field unchanged`() {
        val customer = newWalkInCustomer()
        val user = newActiveUser()

        val linked = useCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, user.id))

        assertEquals(customer.id, linked.id)
        assertEquals(customer.fullName, linked.fullName)
        assertEquals(customer.phoneNumber, linked.phoneNumber)
        assertEquals(customer.email, linked.email)
        assertEquals(customer.company, linked.company)
        assertEquals(customer.status, linked.status)
        assertEquals(user.id, customerRepository.findById(customer.id)?.userId)
    }

    @Test
    fun `rejects a customer that is already linked, without touching activity`() {
        val customer = newWalkInCustomer()
        val firstUser = newActiveUser("first@example.com")
        useCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, firstUser.id))

        val secondUser = newActiveUser("second@example.com")
        assertThrows<CustomerAlreadyLinkedException> {
            useCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, secondUser.id))
        }
        assertEquals(1, customerActivityRepository.findByCustomerId(customer.id).size)
        assertEquals(firstUser.id, customerRepository.findById(customer.id)?.userId)
    }

    @Test
    fun `rejects an unknown customer`() {
        val user = newActiveUser()
        assertThrows<CustomerNotFoundException> {
            useCase.execute(LinkCustomerToUserCommand(CustomerId.new(), ownerId, user.id))
        }
    }

    @Test
    fun `rejects an unknown target user`() {
        val customer = newWalkInCustomer()
        assertThrows<UserNotFoundException> {
            useCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, UserId.new()))
        }
        assertNull(customerRepository.findById(customer.id)?.userId)
    }

    @Test
    fun `rejects a deactivated target user`() {
        val customer = newWalkInCustomer()
        val user = newActiveUser().also { it.deactivate(); userRepository.save(it) }

        assertThrows<InactiveUserException> {
            useCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, user.id))
        }
        assertNull(customerRepository.findById(customer.id)?.userId)
    }

    @Test
    fun `rejects a caller without MANAGE_CRM at this salon`() {
        val customer = newWalkInCustomer()
        val user = newActiveUser()

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(LinkCustomerToUserCommand(customer.id, UserId.new(), user.id))
        }
        assertNull(customerRepository.findById(customer.id)?.userId)
    }

    @Test
    fun `rejects linking to a user already linked to a different customer in the same salon`() {
        val alreadyLinkedCustomer = newWalkInCustomer("+989120000002")
        val user = newActiveUser()
        useCase.execute(LinkCustomerToUserCommand(alreadyLinkedCustomer.id, ownerId, user.id))

        val otherWalkIn = newWalkInCustomer("+989120000003")
        assertThrows<UserAlreadyLinkedToCustomerException> {
            useCase.execute(LinkCustomerToUserCommand(otherWalkIn.id, ownerId, user.id))
        }
        assertNull(customerRepository.findById(otherWalkIn.id)?.userId)
    }

    @Test
    fun `does not affect an unrelated customer's create or update behavior`() {
        val customer = newWalkInCustomer()
        val user = newActiveUser()
        useCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, user.id))

        val untouched = newWalkInCustomer("+989120000009")
        assertNull(untouched.userId)
        untouched.update(fullName = "Untouched Person", phoneNumber = untouched.phoneNumber, email = null, company = null)
        assertEquals("Untouched Person", untouched.fullName)
        assertNull(untouched.userId)
    }
}
