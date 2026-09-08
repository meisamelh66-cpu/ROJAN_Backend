package ai.rojan.backend.infrastructure.persistence.customer

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerAlreadyExistsException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerStatus
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.domain.PageRequest as SpringPageRequest

@Repository
class CustomerRepositoryAdapter(
    private val jpaRepository: CustomerSpringDataRepository,
) : CustomerRepository {

    override fun save(customer: Customer): Customer {
        val entity = jpaRepository.findById(customer.id.value).orElse(null)
            ?.apply {
                userId = customer.userId?.value
                fullName = customer.fullName
                phoneNumber = customer.phoneNumber?.value
                email = customer.email?.value
                company = customer.company
                status = customer.status
                active = customer.active
            }
            ?: CustomerJpaEntity(
                id = customer.id.value,
                salonId = customer.salonId.value,
                userId = customer.userId?.value,
                fullName = customer.fullName,
                phoneNumber = customer.phoneNumber?.value,
                email = customer.email?.value,
                company = customer.company,
                status = customer.status,
                active = customer.active,
            )
        return try {
            // saveAndFlush so a same-salon uniqueness violation (uq_customers_salon_user /
            // uq_customers_salon_phone) surfaces here as a translatable exception rather than
            // later at an opaque flush/commit boundary.
            jpaRepository.saveAndFlush(entity).toDomain()
        } catch (_: DataIntegrityViolationException) {
            // BACKEND-CRM-CUSTOMER-IDENTITY-001: a concurrent request already created the
            // conflicting record. Translate to the domain conflict signal; callers that can
            // recover (ResolveOrCreateSalonCustomerUseCase) re-read and return the winner.
            throw customer.userId?.let { CustomerAlreadyExistsException.forLinkedAccount(it.value.toString()) }
                ?: customer.phoneNumber?.let { CustomerAlreadyExistsException.forPhoneNumber(it.value) }
                ?: CustomerAlreadyExistsException("A conflicting customer record already exists for this salon")
        }
    }

    override fun findById(id: CustomerId): Customer? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): Customer? =
        jpaRepository.findBySalonIdAndUserId(salonId.value, userId.value)?.toDomain()

    override fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        statusFilter: CustomerStatus?,
        tagFilter: String?,
        search: String?,
        sortDirection: SortDirection,
    ): PageResult<Customer> {
        val direction = if (sortDirection == SortDirection.ASC) Sort.Direction.ASC else Sort.Direction.DESC
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size, Sort.by(direction, "fullName"))
        // Pre-formatted here, not concatenated in JPQL - see CustomerSpringDataRepository.findBySalonId's own doc comment.
        val searchPattern = search?.let { "%$it%" }
        val page = jpaRepository.findBySalonId(salonId.value, statusFilter, tagFilter, searchPattern, pageable)
        return PageResult(
            content = page.content.map { it.toDomain() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
        )
    }

    override fun existsBySalonIdAndPhoneNumber(salonId: SalonId, phoneNumber: PhoneNumber): Boolean =
        jpaRepository.existsBySalonIdAndPhoneNumber(salonId.value, phoneNumber.value)

    private fun CustomerJpaEntity.toDomain(): Customer = Customer.reconstitute(
        id = CustomerId(id),
        salonId = SalonId(salonId),
        userId = userId?.let { UserId(it) },
        fullName = fullName,
        phoneNumber = phoneNumber?.let { PhoneNumber(it) },
        email = email?.let { Email(it) },
        company = company,
        status = status,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
