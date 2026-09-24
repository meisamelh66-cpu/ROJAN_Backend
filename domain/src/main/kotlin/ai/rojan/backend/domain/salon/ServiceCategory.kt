package ai.rojan.backend.domain.salon

import java.time.Instant
import java.util.UUID

@JvmInline
value class ServiceCategoryId(val value: UUID) {
    companion object {
        fun new(): ServiceCategoryId = ServiceCategoryId(UUID.randomUUID())
    }
}

/** Groups related [Service]s within a [Salon] (e.g. "Hair", "Nails", "Spa"). */
class ServiceCategory private constructor(
    val id: ServiceCategoryId,
    val salonId: SalonId,
    name: String,
    description: String?,
    active: Boolean,
    isSpecialty: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var name: String = name
        private set

    var description: String? = description
        private set

    var active: Boolean = active
        private set

    /** Salon Completeness (V27) - the approved salon "specialty line" flag (e.g. professional keratin services). Owner-entered, optional-but-answered, never activation-blocking. */
    var isSpecialty: Boolean = isSpecialty
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(name: String, description: String?) {
        require(name.isNotBlank()) { "Service category name must not be blank" }
        this.name = name.trim()
        this.description = description?.trim()?.ifBlank { null }
        this.updatedAt = Instant.now()
    }

    /** Separate from [update] - same "different concern, different method" split this codebase already uses elsewhere (e.g. [ai.rojan.backend.domain.salon.Salon.update] vs. [ai.rojan.backend.domain.salon.Salon.updateProfile]). */
    fun markAsSpecialty(isSpecialty: Boolean) {
        this.isSpecialty = isSpecialty
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        fun create(salonId: SalonId, name: String, description: String?): ServiceCategory {
            require(name.isNotBlank()) { "Service category name must not be blank" }
            val now = Instant.now()
            return ServiceCategory(
                id = ServiceCategoryId.new(),
                salonId = salonId,
                name = name.trim(),
                description = description?.trim()?.ifBlank { null },
                active = true,
                isSpecialty = false,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: ServiceCategoryId,
            salonId: SalonId,
            name: String,
            description: String?,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
            isSpecialty: Boolean = false,
        ): ServiceCategory = ServiceCategory(id, salonId, name, description, active, isSpecialty, createdAt, updatedAt)
    }
}
