package ai.rojan.backend.domain.customer

interface CustomerNoteRepository {
    fun save(note: CustomerNote): CustomerNote
    fun findByCustomerId(customerId: CustomerId): List<CustomerNote>
}
