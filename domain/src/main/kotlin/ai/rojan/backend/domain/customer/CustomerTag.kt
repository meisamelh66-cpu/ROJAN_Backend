package ai.rojan.backend.domain.customer

import java.time.Instant
import java.util.UUID

@JvmInline
value class CustomerTagId(val value: UUID) {
    companion object {
        fun new(): CustomerTagId = CustomerTagId(UUID.randomUUID())
    }
}

/** A single label attached to a [Customer], as returned by [CustomerTagRepository]. */
data class CustomerTag(val id: CustomerTagId, val customerId: CustomerId, val label: String, val createdAt: Instant) {
    companion object {
        fun create(customerId: CustomerId, label: String): CustomerTag {
            require(label.isNotBlank()) { "Tag label must not be blank" }
            return CustomerTag(CustomerTagId.new(), customerId, label.trim(), Instant.now())
        }
    }
}
