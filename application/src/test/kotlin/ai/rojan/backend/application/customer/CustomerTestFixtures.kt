package ai.rojan.backend.application.customer

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerNote
import ai.rojan.backend.domain.customer.CustomerNoteRepository
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerStatus
import ai.rojan.backend.domain.customer.CustomerTag
import ai.rojan.backend.domain.customer.CustomerTagId
import ai.rojan.backend.domain.customer.CustomerTagRepository
import ai.rojan.backend.domain.salon.SalonId

/** Shared in-memory fakes for the Customer CRM use case tests, mirroring `salon.SalonTestFixtures`/`booking.BookingTestFixtures`'s style. */
internal class InMemoryCustomerRepository : CustomerRepository {
    private val store = mutableMapOf<CustomerId, Customer>()

    override fun save(customer: Customer): Customer = customer.also { store[it.id] = it }

    override fun findById(id: CustomerId): Customer? = store[id]

    override fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        statusFilter: CustomerStatus?,
        tagFilter: String?,
        search: String?,
        sortDirection: SortDirection,
    ): PageResult<Customer> {
        val filtered = store.values
            .filter { it.salonId == salonId }
            .filter { statusFilter == null || it.status == statusFilter }
            .filter {
                search.isNullOrBlank() ||
                    it.fullName.contains(search, ignoreCase = true) ||
                    (it.phoneNumber?.value?.contains(search, ignoreCase = true) ?: false) ||
                    (it.email?.value?.contains(search, ignoreCase = true) ?: false)
            }
            .sortedBy { it.fullName }
            .let { if (sortDirection == SortDirection.DESC) it.reversed() else it }
        val fromIndex = (pageRequest.page * pageRequest.size).coerceAtMost(filtered.size)
        val toIndex = (fromIndex + pageRequest.size).coerceAtMost(filtered.size)
        return PageResult(
            content = filtered.subList(fromIndex, toIndex),
            page = pageRequest.page,
            size = pageRequest.size,
            totalElements = filtered.size.toLong(),
        )
    }

    override fun existsBySalonIdAndPhoneNumber(salonId: SalonId, phoneNumber: PhoneNumber): Boolean =
        store.values.any { it.salonId == salonId && it.phoneNumber == phoneNumber }
}

internal class InMemoryCustomerTagRepository : CustomerTagRepository {
    private val store = mutableMapOf<CustomerTagId, CustomerTag>()
    override fun save(tag: CustomerTag): CustomerTag = tag.also { store[it.id] = it }
    override fun findById(id: CustomerTagId): CustomerTag? = store[id]
    override fun findByCustomerId(customerId: CustomerId): List<CustomerTag> = store.values.filter { it.customerId == customerId }
    override fun findByCustomerIdIn(customerIds: Collection<CustomerId>): List<CustomerTag> =
        store.values.filter { it.customerId in customerIds }
    override fun deleteById(id: CustomerTagId) {
        store.remove(id)
    }
}

internal class InMemoryCustomerNoteRepository : CustomerNoteRepository {
    private val store = mutableListOf<CustomerNote>()
    override fun save(note: CustomerNote): CustomerNote = note.also { store.add(it) }
    override fun findByCustomerId(customerId: CustomerId): List<CustomerNote> = store.filter { it.customerId == customerId }
}

internal class InMemoryCustomerActivityRepository : CustomerActivityRepository {
    private val store = mutableListOf<CustomerActivity>()
    override fun save(activity: CustomerActivity): CustomerActivity = activity.also { store.add(it) }
    override fun findByCustomerId(customerId: CustomerId): List<CustomerActivity> = store.filter { it.customerId == customerId }
}
