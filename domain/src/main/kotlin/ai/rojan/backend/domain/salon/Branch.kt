package ai.rojan.backend.domain.salon

import java.time.Instant
import java.util.UUID

@JvmInline
value class BranchId(val value: UUID) {
    companion object {
        fun new(): BranchId = BranchId(UUID.randomUUID())
    }
}

/**
 * A physical location belonging to a [Salon]. Modeled now, ahead of any
 * multi-location booking/routing logic, so a salon can grow into several
 * branches without a later schema migration.
 */
class Branch private constructor(
    val id: BranchId,
    val salonId: SalonId,
    name: String,
    address: String,
    phone: String,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var name: String = name
        private set

    var address: String = address
        private set

    var phone: String = phone
        private set

    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(name: String, address: String, phone: String) {
        require(name.isNotBlank()) { "Branch name must not be blank" }
        require(address.isNotBlank()) { "Branch address must not be blank" }
        require(phone.isNotBlank()) { "Branch phone must not be blank" }
        this.name = name.trim()
        this.address = address.trim()
        this.phone = phone.trim()
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        fun create(salonId: SalonId, name: String, address: String, phone: String): Branch {
            require(name.isNotBlank()) { "Branch name must not be blank" }
            require(address.isNotBlank()) { "Branch address must not be blank" }
            require(phone.isNotBlank()) { "Branch phone must not be blank" }
            val now = Instant.now()
            return Branch(
                id = BranchId.new(),
                salonId = salonId,
                name = name.trim(),
                address = address.trim(),
                phone = phone.trim(),
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: BranchId,
            salonId: SalonId,
            name: String,
            address: String,
            phone: String,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): Branch = Branch(id, salonId, name, address, phone, active, createdAt, updatedAt)
    }
}
