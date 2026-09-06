package ai.rojan.backend.domain.customer

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.InvalidCustomerStateException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private fun newCustomer(
    phoneNumber: PhoneNumber? = PhoneNumber("+989123456789"),
    email: Email? = null,
): Customer = Customer.create(
    salonId = SalonId.new(),
    userId = null,
    fullName = "Jane Doe",
    phoneNumber = phoneNumber,
    email = email,
    company = null,
)

class CustomerTest {

    @Test
    fun `is created as lead with no linked account`() {
        val customer = newCustomer()
        assertEquals(CustomerStatus.LEAD, customer.status)
        assertNull(customer.userId)
        assertEquals(true, customer.active)
    }

    @Test
    fun `rejects a blank full name`() {
        assertThrows(IllegalArgumentException::class.java) {
            Customer.create(SalonId.new(), null, "   ", PhoneNumber("+989123456789"), null, null)
        }
    }

    @Test
    fun `rejects a customer with neither a phone number nor an email`() {
        assertThrows(IllegalArgumentException::class.java) {
            Customer.create(SalonId.new(), null, "Jane Doe", null, null, null)
        }
    }

    @Test
    fun `accepts a customer with only an email and no phone number`() {
        val customer = newCustomer(phoneNumber = null, email = Email("jane@example.com"))
        assertNull(customer.phoneNumber)
        assertEquals("jane@example.com", customer.email?.value)
    }

    @Test
    fun `update changes profile fields`() {
        val customer = newCustomer()
        customer.update("Jane Smith", PhoneNumber("+989987654321"), Email("jane.smith@example.com"), "Acme Corp")
        assertEquals("Jane Smith", customer.fullName)
        assertEquals("+989987654321", customer.phoneNumber?.value)
        assertEquals("jane.smith@example.com", customer.email?.value)
        assertEquals("Acme Corp", customer.company)
    }

    @Test
    fun `update rejects removing both contact methods at once`() {
        val customer = newCustomer()
        assertThrows(IllegalArgumentException::class.java) {
            customer.update("Jane Doe", null, null, null)
        }
    }

    @Test
    fun `linkToUser sets the linked account`() {
        val customer = newCustomer()
        val userId = UserId.new()
        customer.linkToUser(userId)
        assertEquals(userId, customer.userId)
    }

    @Test
    fun `changeStatus allows every documented transition`() {
        val leadToProspect = newCustomer().apply { changeStatus(CustomerStatus.PROSPECT) }
        assertEquals(CustomerStatus.PROSPECT, leadToProspect.status)

        val activeToVip = newCustomer().apply {
            changeStatus(CustomerStatus.ACTIVE)
            changeStatus(CustomerStatus.VIP)
        }
        assertEquals(CustomerStatus.VIP, activeToVip.status)

        // The one non-terminal, win-back path: a churned customer can become a fresh lead again.
        val churnedToLead = newCustomer().apply {
            changeStatus(CustomerStatus.ACTIVE)
            changeStatus(CustomerStatus.CHURNED)
            changeStatus(CustomerStatus.LEAD)
        }
        assertEquals(CustomerStatus.LEAD, churnedToLead.status)
    }

    @Test
    fun `changeStatus rejects an illegal jump`() {
        val customer = newCustomer()
        assertThrows(InvalidCustomerStateException::class.java) {
            customer.changeStatus(CustomerStatus.VIP) // Lead cannot jump straight to Vip
        }
    }

    @Test
    fun `changeStatus rejects moving out of a fully-terminal path incorrectly`() {
        val customer = newCustomer()
        customer.changeStatus(CustomerStatus.CHURNED)
        assertThrows(InvalidCustomerStateException::class.java) {
            customer.changeStatus(CustomerStatus.VIP) // Churned can only go back to Lead
        }
    }

    @Test
    fun `deactivate is idempotent`() {
        val customer = newCustomer()
        customer.deactivate()
        customer.deactivate()
        assertEquals(false, customer.active)
    }
}
