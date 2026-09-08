package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerAlreadyExistsException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerStatus
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** BACKEND-CRM-CUSTOMER-IDENTITY-001 - [ResolveOrCreateSalonCustomerUseCase]. */
class ResolveOrCreateSalonCustomerUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val useCase = ResolveOrCreateSalonCustomerUseCase(salonRepository, customerRepository, userRepository)

    private val account: User = User.register(Email("dana@example.com"), "hash", "Dana Client", UserRole.CUSTOMER)
        .also { userRepository.register(it) }
    private val salon: Salon = Salon.create(account.id, "Glow Salon", null, "+989120000000", null, "1 Main St")
        .also { salonRepository.save(it) }

    private fun command(salonId: SalonId = salon.id, userId: UserId = account.id) =
        ResolveOrCreateSalonCustomerCommand(salonId, userId)

    private fun salonCustomerCount(salonId: SalonId): Long =
        customerRepository.findBySalonId(salonId, PageRequest(0, 100), null, null, null, SortDirection.ASC).totalElements

    @Test
    fun `returns the salon's existing linked record without creating another`() {
        val existing = Customer.create(salon.id, account.id, "Dana Client", null, Email("dana@example.com"), null)
            .also { customerRepository.save(it) }

        val resolved = useCase.execute(command())

        assertEquals(existing.id, resolved.id)
        assertEquals(1L, salonCustomerCount(salon.id))
    }

    @Test
    fun `creates a linked record from the account profile when the salon has none`() {
        assertNull(customerRepository.findBySalonIdAndUserId(salon.id, account.id))

        val created = useCase.execute(command())

        assertEquals(account.id, created.userId)
        assertEquals(salon.id, created.salonId)
        assertEquals("Dana Client", created.fullName)
        assertEquals("dana@example.com", created.email?.value)
        assertNull(created.phoneNumber)
        assertEquals(created.id, customerRepository.findBySalonIdAndUserId(salon.id, account.id)?.id)
    }

    @Test
    fun `the same account at two salons yields two isolated records`() {
        val otherSalon = Salon.create(account.id, "Other Salon", null, "+989120000001", null, "2 Main St")
            .also { salonRepository.save(it) }

        val first = useCase.execute(command(salon.id))
        val second = useCase.execute(command(otherSalon.id))

        assertNotEquals(first.id, second.id)
        assertEquals(salon.id, first.salonId)
        assertEquals(otherSalon.id, second.salonId)
        assertEquals(1L, salonCustomerCount(salon.id))
        assertEquals(1L, salonCustomerCount(otherSalon.id))
    }

    @Test
    fun `is idempotent - a second call returns the same record and creates nothing`() {
        val first = useCase.execute(command())
        val second = useCase.execute(command())

        assertEquals(first.id, second.id)
        assertEquals(1L, salonCustomerCount(salon.id))
    }

    @Test
    fun `rejects an unknown salon`() {
        assertThrows<SalonNotFoundException> { useCase.execute(command(salonId = SalonId.new())) }
    }

    @Test
    fun `rejects an unknown account`() {
        assertThrows<UserNotFoundException> { useCase.execute(command(userId = UserId.new())) }
    }

    @Test
    fun `on a lost create race, catches the conflict, re-reads, and returns the winning record`() {
        val winner = Customer.create(salon.id, account.id, "Dana Client", null, Email("dana@example.com"), null)
        val racing = LosingRaceCustomerRepository(winner)
        val racingUseCase = ResolveOrCreateSalonCustomerUseCase(salonRepository, racing, userRepository)

        val resolved = racingUseCase.execute(command())

        assertEquals(winner.id, resolved.id)
        assertEquals(1, racing.saveAttempts)
        assertEquals(2, racing.lookups) // initial miss, then the post-conflict re-read
    }
}

/**
 * Simulates the concurrency window: the first `(salonId, userId)` lookup misses,
 * `save` then fails as if another request already inserted the row (the DB
 * `uq_customers_salon_user` index rejected this one), and the re-read finds the
 * winner.
 */
private class LosingRaceCustomerRepository(private val winner: Customer) : CustomerRepository {
    var lookups = 0
    var saveAttempts = 0

    override fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): Customer? {
        lookups++
        return if (lookups == 1) null else winner
    }

    override fun save(customer: Customer): Customer {
        saveAttempts++
        throw CustomerAlreadyExistsException.forLinkedAccount(customer.userId!!.value.toString())
    }

    override fun findById(id: CustomerId): Customer? = null
    override fun existsBySalonIdAndPhoneNumber(salonId: SalonId, phoneNumber: PhoneNumber): Boolean = false
    override fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        statusFilter: CustomerStatus?,
        tagFilter: String?,
        search: String?,
        sortDirection: SortDirection,
    ): PageResult<Customer> = PageResult(emptyList(), pageRequest.page, pageRequest.size, 0)
}
