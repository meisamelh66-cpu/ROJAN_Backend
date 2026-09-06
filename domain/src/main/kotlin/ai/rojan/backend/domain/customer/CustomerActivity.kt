package ai.rojan.backend.domain.customer

import java.time.Instant
import java.util.UUID

@JvmInline
value class CustomerActivityId(val value: UUID) {
    companion object {
        fun new(): CustomerActivityId = CustomerActivityId(UUID.randomUUID())
    }
}

/**
 * A CRM-side event for a [Customer] - status changes and tag add/remove
 * only. Deliberately not the sole source of a customer's timeline: the
 * timeline endpoint merges these rows with [CustomerNote]s and booking
 * lifecycle events read directly from `bookings` at query time, rather than
 * every booking-status-transition use case also having to remember to write
 * a row here (see `GetCustomerTimelineUseCase`'s own doc comment for why -
 * that coupling would risk silent, undetectable gaps).
 */
enum class CustomerActivityType {
    STATUS_CHANGED,
    TAG_ADDED,
    TAG_REMOVED,
}

data class CustomerActivity(
    val id: CustomerActivityId,
    val customerId: CustomerId,
    val type: CustomerActivityType,
    val description: String,
    val occurredAt: Instant,
) {
    companion object {
        fun create(customerId: CustomerId, type: CustomerActivityType, description: String): CustomerActivity =
            CustomerActivity(CustomerActivityId.new(), customerId, type, description, Instant.now())
    }
}
