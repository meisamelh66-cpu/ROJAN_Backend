package ai.rojan.backend.domain.salon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Covers `ROJAN_Reception_Permission_Contract_Update_ADR_v1.md`'s final permission mapping. */
class SalonRoleTest {

    @Test
    fun `receptionist gets booking-operational and customer-identity permissions only`() {
        val permissions = SalonRole.RECEPTIONIST.permissions()

        assertEquals(
            setOf(
                Permission.MANAGE_BOOKINGS,
                Permission.VIEW_CUSTOMER_IDENTITY,
                Permission.CREATE_CUSTOMER_IDENTITY,
                Permission.VIEW_CUSTOMER_BOOKING_HISTORY,
            ),
            permissions,
        )
    }

    @Test
    fun `receptionist never receives CRM management or CRM intelligence permissions`() {
        val permissions = SalonRole.RECEPTIONIST.permissions()

        assertFalse(permissions.contains(Permission.VIEW_CRM))
        assertFalse(permissions.contains(Permission.MANAGE_CRM))
        assertFalse(permissions.contains(Permission.MANAGE_CATALOG))
        assertFalse(permissions.contains(Permission.MANAGE_STAFF))
        assertFalse(permissions.contains(Permission.MANAGE_SCHEDULE_ALL))
    }

    @Test
    fun `manager retains full CRM access plus the new customer-identity permissions explicitly`() {
        val permissions = SalonRole.MANAGER.permissions()

        assertEquals(
            setOf(
                Permission.MANAGE_CATALOG,
                Permission.MANAGE_STAFF,
                Permission.MANAGE_SCHEDULE_ALL,
                Permission.VIEW_CRM,
                Permission.MANAGE_CRM,
                Permission.MANAGE_BOOKINGS,
                Permission.VIEW_CUSTOMER_IDENTITY,
                Permission.CREATE_CUSTOMER_IDENTITY,
                Permission.VIEW_CUSTOMER_BOOKING_HISTORY,
            ),
            permissions,
        )
    }
}
