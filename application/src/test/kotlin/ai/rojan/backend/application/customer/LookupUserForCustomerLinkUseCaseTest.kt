package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.customer.Customer
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

class LookupUserForCustomerLinkUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val useCase = LookupUserForCustomerLinkUseCase(salonRepository, customerRepository, userRepository, salonPermissionResolver)
    private val linkUseCase = LinkCustomerToUserUseCase(
        salonRepository,
        customerRepository,
        userRepository,
        InMemoryCustomerActivityRepository(),
        salonPermissionResolver,
    )

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    private fun newWalkInCustomer(phone: String?) =
        Customer.create(
            salonId = salon.id,
            userId = null,
            fullName = "Jane Doe",
            phoneNumber = phone?.let { PhoneNumber(it) },
            email = if (phone == null) Email("jane@example.com") else null,
            company = null,
        ).also { customerRepository.save(it) }

    private fun newUser(phone: String, active: Boolean = true) =
        User.registerWithPhone(PhoneNumber(phone), "Real Jane", UserRole.CUSTOMER)
            .also { if (!active) it.deactivate() }
            .also { userRepository.register(it) }

    @Test
    fun `finds the active user matching the customer's own phone number`() {
        val customer = newWalkInCustomer("+989120000001")
        val user = newUser("+989120000001")

        val candidate = useCase.execute(LookupUserForCustomerLinkCommand(customer.id, ownerId))

        assertEquals(user.id, candidate.userId)
        assertEquals(user.fullName, candidate.fullName)
        assertEquals("+989120000001", candidate.phoneNumber)
    }

    @Test
    fun `returns not found when no user matches the customer's phone`() {
        val customer = newWalkInCustomer("+989120000002")

        assertThrows<UserNotFoundException> {
            useCase.execute(LookupUserForCustomerLinkCommand(customer.id, ownerId))
        }
    }

    @Test
    fun `treats a matching but inactive user exactly like no match`() {
        val customer = newWalkInCustomer("+989120000003")
        newUser("+989120000003", active = false)

        assertThrows<UserNotFoundException> {
            useCase.execute(LookupUserForCustomerLinkCommand(customer.id, ownerId))
        }
    }

    @Test
    fun `returns not found when the customer has no phone number on file`() {
        val customer = newWalkInCustomer(phone = null)

        assertThrows<UserNotFoundException> {
            useCase.execute(LookupUserForCustomerLinkCommand(customer.id, ownerId))
        }
    }

    @Test
    fun `rejects an unknown customer`() {
        assertThrows<CustomerNotFoundException> {
            useCase.execute(LookupUserForCustomerLinkCommand(CustomerId.new(), ownerId))
        }
    }

    @Test
    fun `rejects a caller without MANAGE_CRM at this salon`() {
        val customer = newWalkInCustomer("+989120000004")
        newUser("+989120000004")

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(LookupUserForCustomerLinkCommand(customer.id, UserId.new()))
        }
    }

    @Test
    fun `the lookup never mutates the customer or the user`() {
        val customer = newWalkInCustomer("+989120000005")
        val user = newUser("+989120000005")

        useCase.execute(LookupUserForCustomerLinkCommand(customer.id, ownerId))

        val unchangedCustomer = customerRepository.findById(customer.id)!!
        assertNull(unchangedCustomer.userId)
        assertEquals(customer.updatedAt, unchangedCustomer.updatedAt)

        val unchangedUser = userRepository.findById(user.id)!!
        assertEquals(user.fullName, unchangedUser.fullName)
        assertEquals(true, unchangedUser.active)
    }

    @Test
    fun `existing customer linking behavior via the real link use case is unaffected by this lookup`() {
        val customer = newWalkInCustomer("+989120000006")
        val user = newUser("+989120000006")

        val candidate = useCase.execute(LookupUserForCustomerLinkCommand(customer.id, ownerId))
        val linked = linkUseCase.execute(LinkCustomerToUserCommand(customer.id, ownerId, candidate.userId))

        assertEquals(user.id, linked.userId)
    }
}
