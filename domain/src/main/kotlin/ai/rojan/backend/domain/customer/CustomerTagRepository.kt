package ai.rojan.backend.domain.customer

interface CustomerTagRepository {
    fun save(tag: CustomerTag): CustomerTag
    fun findById(id: CustomerTagId): CustomerTag?
    fun findByCustomerId(customerId: CustomerId): List<CustomerTag>
    fun deleteById(id: CustomerTagId)
}
