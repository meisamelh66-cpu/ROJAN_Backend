package ai.rojan.backend.domain.customer

interface CustomerActivityRepository {
    fun save(activity: CustomerActivity): CustomerActivity
    fun findByCustomerId(customerId: CustomerId): List<CustomerActivity>
}
