package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.SalonNotActiveException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnsureCustomerAssociationUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val useCase = EnsureCustomerAssociationUseCase(salonRepository, customerRepository, userRepository)

    private val salon = salonRepository.save(
        Salon.create(UserId.new(), "Glow Salon", null, "+1 555 0100", null, "1 Main St", onboardingStatus = SalonOnboardingStatus.ACTIVE),
    )
    private val salonId = salon.id

    @Test
    fun `creates a customer linked to the caller on first association`() {
        val user = User.register(Email("ada@example.com"), "hash", "Ada Lovelace", UserRole.CUSTOMER)
        userRepository.register(user)

        val customer = useCase.execute(EnsureCustomerAssociationCommand(salonId, user.id))

        assertEquals(salonId, customer.salonId)
        assertEquals(user.id, customer.userId)
        assertEquals("Ada Lovelace", customer.fullName)
        assertEquals(user.email, customer.email)
        assertNull(customer.phoneNumber)
    }

    @Test
    fun `sources phone number from a phone-registered user`() {
        val user = User.registerWithPhone(PhoneNumber("+15550100"), "Grace Hopper", UserRole.CUSTOMER)
        userRepository.register(user)

        val customer = useCase.execute(EnsureCustomerAssociationCommand(salonId, user.id))

        assertEquals(user.phoneNumber, customer.phoneNumber)
        assertNull(customer.email)
    }

    @Test
    fun `returns the same customer on a repeat association instead of creating a duplicate`() {
        val user = User.register(Email("ada@example.com"), "hash", "Ada Lovelace", UserRole.CUSTOMER)
        userRepository.register(user)

        val first = useCase.execute(EnsureCustomerAssociationCommand(salonId, user.id))
        val second = useCase.execute(EnsureCustomerAssociationCommand(salonId, user.id))

        assertEquals(first.id, second.id)
    }

    @Test
    fun `fails when the caller's user record does not exist`() {
        assertThrows<UserNotFoundException> {
            useCase.execute(EnsureCustomerAssociationCommand(salonId, UserId.new()))
        }
    }

    @Test
    fun `rejects association with a salon that has not been activated`() {
        val draftSalon = salonRepository.save(
            Salon.create(UserId.new(), "Draft Salon", null, "+1 555 0199", null, "2 Main St", onboardingStatus = SalonOnboardingStatus.DRAFT),
        )
        val user = User.register(Email("grace@example.com"), "hash", "Grace Hopper", UserRole.CUSTOMER)
        userRepository.register(user)

        assertThrows<SalonNotActiveException> {
            useCase.execute(EnsureCustomerAssociationCommand(draftSalon.id, user.id))
        }
    }
}
