package ai.rojan.backend.domain.salon

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JvmInline
value class ServiceId(val value: UUID) {
    companion object {
        fun new(): ServiceId = ServiceId(UUID.randomUUID())
    }
}

/** A bookable service offered by a [Salon] under a [ServiceCategory]. */
class Service private constructor(
    val id: ServiceId,
    val salonId: SalonId,
    val categoryId: ServiceCategoryId,
    name: String,
    description: String?,
    durationMinutes: Int,
    price: BigDecimal,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var name: String = name
        private set

    var description: String? = description
        private set

    var durationMinutes: Int = durationMinutes
        private set

    var price: BigDecimal = price
        private set

    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(name: String, description: String?, durationMinutes: Int, price: BigDecimal) {
        require(name.isNotBlank()) { "Service name must not be blank" }
        require(durationMinutes > 0) { "Service duration must be positive" }
        require(price.signum() > 0) { "Service price must be positive" }
        this.name = name.trim()
        this.description = description?.trim()?.ifBlank { null }
        this.durationMinutes = durationMinutes
        this.price = price
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            salonId: SalonId,
            categoryId: ServiceCategoryId,
            name: String,
            description: String?,
            durationMinutes: Int,
            price: BigDecimal,
        ): Service {
            require(name.isNotBlank()) { "Service name must not be blank" }
            require(durationMinutes > 0) { "Service duration must be positive" }
            require(price.signum() > 0) { "Service price must be positive" }
            val now = Instant.now()
            return Service(
                id = ServiceId.new(),
                salonId = salonId,
                categoryId = categoryId,
                name = name.trim(),
                description = description?.trim()?.ifBlank { null },
                durationMinutes = durationMinutes,
                price = price,
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: ServiceId,
            salonId: SalonId,
            categoryId: ServiceCategoryId,
            name: String,
            description: String?,
            durationMinutes: Int,
            price: BigDecimal,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): Service = Service(
            id, salonId, categoryId, name, description, durationMinutes, price, active, createdAt, updatedAt,
        )
    }
}
