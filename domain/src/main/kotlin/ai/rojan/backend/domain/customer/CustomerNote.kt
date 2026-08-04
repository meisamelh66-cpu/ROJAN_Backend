package ai.rojan.backend.domain.customer

import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class CustomerNoteId(val value: UUID) {
    companion object {
        fun new(): CustomerNoteId = CustomerNoteId(UUID.randomUUID())
    }
}

/** A free-text note attached to a [Customer], as returned by [CustomerNoteRepository]. [authorId] is whichever staff account wrote it - the backend serves multiple logins per salon, unlike a single-owner desktop client. */
data class CustomerNote(val id: CustomerNoteId, val customerId: CustomerId, val authorId: UserId, val text: String, val createdAt: Instant) {
    companion object {
        fun create(customerId: CustomerId, authorId: UserId, text: String): CustomerNote {
            require(text.isNotBlank()) { "Note text must not be blank" }
            return CustomerNote(CustomerNoteId.new(), customerId, authorId, text.trim(), Instant.now())
        }
    }
}
