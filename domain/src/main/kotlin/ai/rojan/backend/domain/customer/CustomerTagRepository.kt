package ai.rojan.backend.domain.customer

interface CustomerTagRepository {
    fun save(tag: CustomerTag): CustomerTag
    fun findById(id: CustomerTagId): CustomerTag?
    fun findByCustomerId(customerId: CustomerId): List<CustomerTag>

    /**
     * Production Hardening Phase 1: batched sibling of [findByCustomerId],
     * one query for every customer on a paginated list page instead of one
     * query per row - callers group the result by [CustomerTag.customerId]
     * themselves.
     */
    fun findByCustomerIdIn(customerIds: Collection<CustomerId>): List<CustomerTag>
    fun deleteById(id: CustomerTagId)
}
