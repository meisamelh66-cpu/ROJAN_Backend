package ai.rojan.backend.domain.customer

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.InvalidCustomerStateException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class CustomerId(val value: UUID) {
    companion object {
        fun new(): CustomerId = CustomerId(UUID.randomUUID())
    }
}

enum class CustomerStatus {
    LEAD,
    PROSPECT,
    ACTIVE,
    VIP,
    INACTIVE,
    CHURNED,
}

/**
 * A salon's CRM record for a person - deliberately distinct from [ai.rojan.backend.domain.user.User],
 * not a repurposing of it. [userId] is optional, mirroring [ai.rojan.backend.domain.salon.Specialist]'s
 * own nullable account link: a real salon has customers who never create an
 * app account (walk-ins, phone bookings, manually-added regulars), and CRM
 * data (status, notes, tags) is owned by one salon, never shared across the
 * salons a person might interact with - collapsing this into `User` would
 * leak one salon's private notes about a person into another salon's view
 * of the same account. A booking's history is only resolvable for a
 * customer once [userId] is linked (see [linkToUser]'s own doc comment).
 */
class Customer private constructor(
    val id: CustomerId,
    val salonId: SalonId,
    userId: UserId?,
    fullName: String,
    phoneNumber: PhoneNumber?,
    email: Email?,
    company: String?,
    status: CustomerStatus,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var userId: UserId? = userId
        private set

    var fullName: String = fullName
        private set

    var phoneNumber: PhoneNumber? = phoneNumber
        private set

    var email: Email? = email
        private set

    var company: String? = company
        private set

    var status: CustomerStatus = status
        private set

    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(fullName: String, phoneNumber: PhoneNumber?, email: Email?, company: String?) {
        require(fullName.isNotBlank()) { "Customer full name must not be blank" }
        require(phoneNumber != null || email != null) { "Customer must have a phone number or an email" }
        this.fullName = fullName.trim()
        this.phoneNumber = phoneNumber
        this.email = email
        this.company = company?.trim()?.ifBlank { null }
        this.updatedAt = Instant.now()
    }

    /**
     * Moves this customer's relationship stage. Every status has at least
     * one way out, including [CustomerStatus.CHURNED] -&gt; [CustomerStatus.LEAD]
     * (a win-back campaign re-engaging a former customer as a fresh lead) -
     * unlike a booking's one-shot lifecycle, a customer relationship is
     * ongoing, so nothing here is fully terminal. Callers must only invoke
     * this for an actual change (`newStatus != status`) - editing other
     * fields without changing status is not a transition and must never be
     * rejected by this check.
     */
    fun changeStatus(newStatus: CustomerStatus) {
        val allowed = VALID_TRANSITIONS[status].orEmpty()
        if (newStatus !in allowed) {
            throw InvalidCustomerStateException("Cannot transition customer from $status to $newStatus")
        }
        status = newStatus
        updatedAt = Instant.now()
    }

    /**
     * Links this CRM record to a real backend account - e.g. once a
     * manually-added walk-in later signs up via Mobile OTP with a matching
     * phone number (a manual/future reconciliation step; nothing calls this
     * automatically today - see `ROJAN_Customer_CRM_Architecture_Plan_v1.md`
     * §6.4). Booking history and lifetime value only become resolvable for
     * this customer once linked, since [ai.rojan.backend.domain.booking.Booking.customerId]
     * only ever references a [UserId], never a [CustomerId] directly.
     */
    fun linkToUser(userId: UserId) {
        this.userId = userId
        updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        private val VALID_TRANSITIONS: Map<CustomerStatus, Set<CustomerStatus>> = mapOf(
            CustomerStatus.LEAD to setOf(CustomerStatus.PROSPECT, CustomerStatus.ACTIVE, CustomerStatus.CHURNED),
            CustomerStatus.PROSPECT to setOf(CustomerStatus.ACTIVE, CustomerStatus.CHURNED),
            CustomerStatus.ACTIVE to setOf(CustomerStatus.VIP, CustomerStatus.INACTIVE, CustomerStatus.CHURNED),
            CustomerStatus.VIP to setOf(CustomerStatus.ACTIVE, CustomerStatus.INACTIVE, CustomerStatus.CHURNED),
            CustomerStatus.INACTIVE to setOf(CustomerStatus.ACTIVE, CustomerStatus.CHURNED),
            CustomerStatus.CHURNED to setOf(CustomerStatus.LEAD),
        )

        fun create(
            salonId: SalonId,
            userId: UserId?,
            fullName: String,
            phoneNumber: PhoneNumber?,
            email: Email?,
            company: String?,
        ): Customer {
            require(fullName.isNotBlank()) { "Customer full name must not be blank" }
            require(phoneNumber != null || email != null) { "Customer must have a phone number or an email" }
            val now = Instant.now()
            return Customer(
                id = CustomerId.new(),
                salonId = salonId,
                userId = userId,
                fullName = fullName.trim(),
                phoneNumber = phoneNumber,
                email = email,
                company = company?.trim()?.ifBlank { null },
                status = CustomerStatus.LEAD,
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: CustomerId,
            salonId: SalonId,
            userId: UserId?,
            fullName: String,
            phoneNumber: PhoneNumber?,
            email: Email?,
            company: String?,
            status: CustomerStatus,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): Customer = Customer(id, salonId, userId, fullName, phoneNumber, email, company, status, active, createdAt, updatedAt)
    }
}
