package ai.rojan.backend.domain.customer

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId

interface CustomerRepository {
    fun save(customer: Customer): Customer
    fun findById(id: CustomerId): Customer?

    /**
     * The salon's CRM record for a specific linked account, if one exists -
     * the `(salonId, userId)` lookup [ai.rojan.backend.domain.customer.Customer]
     * resolution keys on (see `ResolveOrCreateSalonCustomerUseCase` and
     * `CreateCustomerUseCase`'s optional-link path). Salon-scoped: at most one
     * linked record per account per salon, enforced by the
     * `uq_customers_salon_user` partial unique index. Walk-in records (null
     * `userId`) are exempt and never matched here.
     */
    fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): Customer?

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
