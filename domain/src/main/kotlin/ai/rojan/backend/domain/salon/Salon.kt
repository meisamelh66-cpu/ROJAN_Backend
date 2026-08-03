package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonId(val value: UUID) {
    companion object {
        fun new(): SalonId = SalonId(UUID.randomUUID())
    }
}

/**
 * Aggregate root for a salon business. Construction is only possible through
 * [create] (new salons) or [reconstitute] (rehydration from storage) so
 * invariants can never be bypassed.
 */
class Salon private constructor(
    val id: SalonId,
    val ownerId: UserId,
    name: String,
    description: String?,
    phone: String,
    email: String?,
    address: String,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var name: String = name
        private set

    var description: String? = description
        private set

    var phone: String = phone
        private set

    var email: String? = email
        private set

    var address: String = address
        private set

    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(
        name: String,
        description: String?,
        phone: String,
        email: String?,
        address: String,
    ) {
        require(name.isNotBlank()) { "Salon name must not be blank" }
        require(phone.isNotBlank()) { "Salon phone must not be blank" }
        require(address.isNotBlank()) { "Salon address must not be blank" }
        this.name = name.trim()
        this.description = description?.trim()?.ifBlank { null }
        this.phone = phone.trim()
        this.email = email?.trim()?.ifBlank { null }
        this.address = address.trim()
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            ownerId: UserId,
            name: String,
            description: String?,
            phone: String,
            email: String?,
            address: String,
        ): Salon {
            require(name.isNotBlank()) { "Salon name must not be blank" }
            require(phone.isNotBlank()) { "Salon phone must not be blank" }
            require(address.isNotBlank()) { "Salon address must not be blank" }
            val now = Instant.now()
            return Salon(
                id = SalonId.new(),
                ownerId = ownerId,
                name = name.trim(),
                description = description?.trim()?.ifBlank { null },
                phone = phone.trim(),
                email = email?.trim()?.ifBlank { null },
                address = address.trim(),
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: SalonId,
            ownerId: UserId,
            name: String,
            description: String?,
            phone: String,
            email: String?,
            address: String,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): Salon = Salon(id, ownerId, name, description, phone, email, address, active, createdAt, updatedAt)
    }
}
