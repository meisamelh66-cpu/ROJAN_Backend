package ai.rojan.backend.bootstrap

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.UUID

/**
 * BACKEND-CRM-CUSTOMER-IDENTITY-001 (Phase 2) - verifies the V8 backfill
 * migration against a real embedded PostgreSQL, seeding "historical" data
 * (users + bookings, no CRM records) directly via JDBC the way a
 * pre-CRM production database looks, then running the shipped V8 SQL.
 *
 * V8 already ran once (on the empty schema) during Flyway migration; this
 * test re-executes the exact file contents to prove: correctness, salon
 * isolation, reuse of an existing linked record, and idempotency on repeat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class BookingCustomerBackfillIntegrationTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val backfillSql: List<String> by lazy {
        ClassPathResource("db/migration/V8__backfill_booking_customers.sql")
            .inputStream.bufferedReader().readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun runBackfill() = backfillSql.forEach { jdbc.execute(it) }

    private fun seedUser(name: String, role: String = "CUSTOMER"): UUID = UUID.randomUUID().also {
        jdbc.update(
            "INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, 'x', ?, ?)",
            it, "$name.${System.nanoTime()}@example.com", name, role,
        )
    }

    private fun seedSalon(owner: UUID, name: String): UUID = UUID.randomUUID().also {
        jdbc.update(
            "INSERT INTO salons (id, owner_id, name, phone, address) VALUES (?, ?, ?, '0912', 'Addr')",
            it, owner, name,
        )
    }

    private fun seedServiceAndSpecialist(salon: UUID): Pair<UUID, UUID> {
        val category = UUID.randomUUID()
        jdbc.update("INSERT INTO service_categories (id, salon_id, name) VALUES (?, ?, 'Cat')", category, salon)
        val service = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO services (id, salon_id, category_id, name, duration_minutes, price) VALUES (?, ?, ?, 'Svc', 30, 25.00)",
            service, salon, category,
        )
        val specialist = UUID.randomUUID()
        jdbc.update("INSERT INTO specialists (id, salon_id, display_name) VALUES (?, ?, 'Spec')", specialist, salon)
        return service to specialist
    }

    private fun seedBooking(salon: UUID, customerUserId: UUID, service: UUID, specialist: UUID, hour: Int) {
        val start = LocalDateTime.of(2026, 1, 1, hour, 0)
        jdbc.update(
            """INSERT INTO bookings (id, salon_id, service_id, specialist_id, customer_id, start_time, end_time, status)
               VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED')""",
            UUID.randomUUID(), salon, service, specialist, customerUserId, start, start.plusMinutes(30),
        )
    }

    private fun customerCount(salon: UUID, user: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM customers WHERE salon_id = ? AND user_id = ?",
            Int::class.java, salon, user,
        )!!

    private fun bookingCustomerId(salon: UUID, user: UUID): UUID? =
        jdbc.query(
            "SELECT salon_customer_id FROM bookings WHERE salon_id = ? AND customer_id = ?",
            { rs, _ -> rs.getObject("salon_customer_id", UUID::class.java) },
            salon, user,
        ).firstOrNull()

    @Test
    fun `backfill links historical booking customers, isolates salons, reuses existing records, and is idempotent`() {
        val ownerA = seedUser("Owner A", role = "MANAGER")
        val ownerB = seedUser("Owner B", role = "MANAGER")
        val salonA = seedSalon(ownerA, "Salon A")
        val salonB = seedSalon(ownerB, "Salon B")
        val (svcA, specA) = seedServiceAndSpecialist(salonA)
        val (svcB, specB) = seedServiceAndSpecialist(salonB)

        val custX = seedUser("Cust X") // books at BOTH salons
        val custY = seedUser("Cust Y") // books at salon A only
        val custZ = seedUser("Cust Z") // books at salon A, ALREADY has a linked CRM record

        seedBooking(salonA, custX, svcA, specA, 9)
        seedBooking(salonB, custX, svcB, specB, 9)
        seedBooking(salonA, custY, svcA, specA, 10)
        seedBooking(salonA, custZ, svcA, specA, 11)

        val preExistingZ = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO customers (id, salon_id, user_id, full_name, email, status, active) VALUES (?, ?, ?, 'Cust Z', 'z@x.com', 'VIP', TRUE)",
            preExistingZ, salonA, custZ,
        )

        runBackfill()

        // one linked record per (salon, user); custX has two, one per salon
        assertEquals(1, customerCount(salonA, custX))
        assertEquals(1, customerCount(salonB, custX))
        assertEquals(1, customerCount(salonA, custY))
        assertEquals(1, customerCount(salonA, custZ)) // reused, not duplicated

        // pre-existing record reused verbatim (status VIP preserved, not reset to LEAD)
        assertEquals(
            "VIP",
            jdbc.queryForObject("SELECT status FROM customers WHERE id = ?", String::class.java, preExistingZ),
        )

        // every historical booking now points at its salon's record
        val xAtA = bookingCustomerId(salonA, custX)
        val xAtB = bookingCustomerId(salonB, custX)
        assertNotNull(xAtA)
        assertNotNull(xAtB)
        assertNotEquals(xAtA, xAtB) // salon isolation
        assertEquals(preExistingZ, bookingCustomerId(salonA, custZ))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bookings WHERE salon_customer_id IS NULL", Int::class.java))

        // idempotent: a second run changes nothing
        val customersAfterFirst = jdbc.queryForObject("SELECT count(*) FROM customers", Int::class.java)
        runBackfill()
        assertEquals(customersAfterFirst, jdbc.queryForObject("SELECT count(*) FROM customers", Int::class.java))
        assertEquals(xAtA, bookingCustomerId(salonA, custX))
    }
}
