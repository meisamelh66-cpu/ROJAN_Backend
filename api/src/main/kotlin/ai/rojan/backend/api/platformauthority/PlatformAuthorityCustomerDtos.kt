package ai.rojan.backend.api.platformauthority

import java.time.Instant
import java.util.UUID

/**
 * Platform Management API Contract (Customer): [User][ai.rojan.backend.domain.user.User]-level
 * identity fields only - deliberately no salon association of any kind (unlike
 * [PlatformManagerResponse]), and structurally impossible to carry salon-private CRM data
 * ([ai.rojan.backend.domain.customer.Customer] notes/tags/lifetime-value/timeline) since this type
 * is built exclusively from [ai.rojan.backend.domain.user.User], a completely different aggregate
 * this contract never queries. See this contract's own design report for the full boundary.
 */
data class PlatformCustomerAccountResponse(
    val id: UUID,
    val phoneNumber: String?,
    val fullName: String,
    val active: Boolean,
    val createdAt: Instant,
)
