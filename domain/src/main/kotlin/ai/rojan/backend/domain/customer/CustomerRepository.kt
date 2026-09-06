package ai.rojan.backend.domain.customer

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.SalonId

interface CustomerRepository {
    fun save(customer: Customer): Customer
    fun findById(id: CustomerId): Customer?

    /** A salon's customers, optionally filtered by status/tag/free-text search (name, phone, email), sorted by full name. */
    fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        statusFilter: CustomerStatus?,
        tagFilter: String?,
        search: String?,
        sortDirection: SortDirection,
    ): PageResult<Customer>

    /** Used to reject a duplicate walk-in record for the same phone number within one salon (see `CreateCustomerUseCase`). */
    fun existsBySalonIdAndPhoneNumber(salonId: SalonId, phoneNumber: PhoneNumber): Boolean
}
