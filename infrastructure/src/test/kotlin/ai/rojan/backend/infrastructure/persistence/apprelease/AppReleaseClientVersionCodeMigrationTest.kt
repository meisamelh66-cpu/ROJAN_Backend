package ai.rojan.backend.infrastructure.persistence.apprelease

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * App Release Update-Check Fix (V52 + V53 + V54): proves all three migrations' effects in
 * isolation. A `@SpringBootTest` always runs every migration before the Spring context - and
 * therefore the test - ever starts, so it can never observe a pre-V52 row or a not-yet-corrected
 * one. This test drives Flyway's own Java API directly against a disposable embedded Postgres
 * instead: migrate only through V51, insert raw rows shaped exactly like real pre-existing
 * production rows (no client_version_code column yet), then migrate to latest and assert:
 * - V52 backfills every row to client_version_code == version_code (true for every app, including
 *   Manager's own 1.0.6/versionCode=7 and 1.0.7/versionCode=8 rows, immediately after V52 and
 *   before V53/V54 run);
 * - V53 then corrects only the one specific, already-known Manager 1.0.6/versionCode=7 row to
 *   client_version_code=6, leaving every other row untouched;
 * - V54 then corrects only the Manager 1.0.7/versionCode=8 row to client_version_code=7, leaving
 *   the already-corrected 1.0.6 row and every other row (Customer, Reception, and any other
 *   Manager release that doesn't match that exact versionCode+versionName) untouched;
 * - both V53 and V54 are safe no-ops on a database with no matching row at all (every fresh
 *   test/dev environment).
 */
class AppReleaseClientVersionCodeMigrationTest {

    private lateinit var embeddedPostgres: EmbeddedPostgres

    @BeforeEach
    fun startDatabase() {
        embeddedPostgres = EmbeddedPostgres.builder().start()
    }

    @AfterEach
    fun stopDatabase() {
        embeddedPostgres.close()
    }

    private fun flyway(target: String? = null) = Flyway.configure()
        .dataSource(embeddedPostgres.postgresDatabase)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(MigrationVersion.fromVersion(target)) }
        .load()

    @Test
    fun `every pre-existing row is backfilled to client_version_code equal to its own version_code`() {
        flyway("51").migrate()

        // Deliberately excludes the Manager 1.0.6/versionCode=7 shape - V53 (which also runs when
        // migrating to latest) corrects that one specific row away from this general rule; its own
        // behavior is covered separately below.
        val legacyRows = listOf(
            Triple("ai.rojan.designlab", "2.0.0", 20),
            Triple("ai.rojan.designlab.reception", "1.0.1", 1_000_001),
        )
        embeddedPostgres.postgresDatabase.connection.use { connection ->
            legacyRows.forEach { (applicationId, versionName, versionCode) ->
                insertLegacyAppRelease(connection, applicationId, versionName, versionCode)
            }
        }

        flyway().migrate() // brings the schema to latest - V52 runs here and must backfill every row above.

        embeddedPostgres.postgresDatabase.connection.use { connection ->
            legacyRows.forEach { (applicationId, _, versionCode) ->
                assertEquals(
                    versionCode,
                    clientVersionCodeOf(connection, applicationId, versionCode),
                    "$applicationId's pre-existing row must backfill client_version_code to its own version_code",
                )
            }
        }
    }

    @Test
    fun `V53 corrects only the Manager 1-0-6 versionCode=7 row, leaving every other row untouched`() {
        flyway("51").migrate()

        val rows = listOf(
            Triple("ai.rojan.designlab.manager", "1.0.6", 7), // the real incident row
            Triple("ai.rojan.designlab", "2.0.0", 20),
            Triple("ai.rojan.designlab.reception", "1.0.1", 1_000_001),
        )
        embeddedPostgres.postgresDatabase.connection.use { connection ->
            rows.forEach { (applicationId, versionName, versionCode) ->
                insertLegacyAppRelease(connection, applicationId, versionName, versionCode)
            }
        }

        flyway().migrate() // V52 backfills all three to client_version_code == version_code, then V53 corrects only the Manager row.

        embeddedPostgres.postgresDatabase.connection.use { connection ->
            assertEquals(6, clientVersionCodeOf(connection, "ai.rojan.designlab.manager", 7), "the known Manager 1.0.6 incident row must be corrected to 6")
            assertEquals(20, clientVersionCodeOf(connection, "ai.rojan.designlab", 20), "Customer's row must be untouched by the Manager-only correction")
            assertEquals(1_000_001, clientVersionCodeOf(connection, "ai.rojan.designlab.reception", 1_000_001), "Reception's row must be untouched by the Manager-only correction")
        }
    }

    @Test
    fun `a Manager row with a different versionCode or versionName than either known incident is never touched`() {
        flyway("51").migrate()

        // Matches neither known incident shape (versionCode=7/versionName="1.0.6" for V53, or
        // versionCode=8/versionName="1.0.7" for V54) - the corrections must never touch either.
        val otherManagerRows = listOf(
            Triple("ai.rojan.designlab.manager", "1.0.8", 9),
            Triple("ai.rojan.designlab.manager", "1.0.5", 5),
        )
        embeddedPostgres.postgresDatabase.connection.use { connection ->
            otherManagerRows.forEach { (applicationId, versionName, versionCode) ->
                insertLegacyAppRelease(connection, applicationId, versionName, versionCode)
            }
        }

        flyway().migrate()

        embeddedPostgres.postgresDatabase.connection.use { connection ->
            otherManagerRows.forEach { (_, _, versionCode) ->
                assertEquals(
                    versionCode,
                    clientVersionCodeOf(connection, "ai.rojan.designlab.manager", versionCode),
                    "a non-matching Manager row must keep client_version_code == version_code",
                )
            }
        }
    }

    @Test
    fun `V53 is a no-op, and does not fail, on a database with no matching row at all`() {
        // No rows inserted - every fresh test/dev database, and any environment where this
        // incident never happened, must sail through V53 untouched.
        flyway().migrate()
    }

    @Test
    fun `V54 corrects only the Manager 1-0-7 versionCode=8 row, leaving the 1-0-6 row and every other row untouched`() {
        flyway("51").migrate()

        val rows = listOf(
            Triple("ai.rojan.designlab.manager", "1.0.6", 7), // V53's own incident row - must stay corrected to 6, not re-touched by V54
            Triple("ai.rojan.designlab.manager", "1.0.7", 8), // the new incident row - V54's own target
            Triple("ai.rojan.designlab", "2.0.0", 20),
            Triple("ai.rojan.designlab.reception", "1.0.1", 1_000_001),
        )
        embeddedPostgres.postgresDatabase.connection.use { connection ->
            rows.forEach { (applicationId, versionName, versionCode) ->
                insertLegacyAppRelease(connection, applicationId, versionName, versionCode)
            }
        }

        flyway().migrate() // V52 backfills all four, V53 corrects the 1.0.6 row to 6, V54 corrects the 1.0.7 row to 7.

        embeddedPostgres.postgresDatabase.connection.use { connection ->
            assertEquals(6, clientVersionCodeOf(connection, "ai.rojan.designlab.manager", 7), "V53's own 1.0.6 incident row must remain corrected to 6, untouched by V54")
            assertEquals(7, clientVersionCodeOf(connection, "ai.rojan.designlab.manager", 8), "the new Manager 1.0.7 incident row must be corrected to 7")
            assertEquals(20, clientVersionCodeOf(connection, "ai.rojan.designlab", 20), "Customer's row must be untouched by the Manager-only correction")
            assertEquals(1_000_001, clientVersionCodeOf(connection, "ai.rojan.designlab.reception", 1_000_001), "Reception's row must be untouched by the Manager-only correction")
        }
    }

    @Test
    fun `V54 is a no-op, and does not fail, on a database with no matching row at all`() {
        // No rows inserted - every fresh test/dev database, and any environment where this
        // second incident never happened, must sail through V54 untouched.
        flyway().migrate()
    }

    private fun clientVersionCodeOf(connection: Connection, applicationId: String, versionCode: Int): Int =
        connection.prepareStatement(
            "SELECT client_version_code FROM app_releases WHERE application_id = ? AND version_code = ?",
        ).apply {
            setString(1, applicationId)
            setInt(2, versionCode)
        }.executeQuery().use { rs ->
            assertTrue(rs.next(), "expected a row for $applicationId / $versionCode")
            rs.getInt("client_version_code")
        }

    /** Inserts a row shaped exactly like a real pre-V52 app_releases row (plus the users row its created_by FK needs) - no client_version_code column reference at all, since it does not exist yet at this point in the migration sequence. */
    private fun insertLegacyAppRelease(connection: Connection, applicationId: String, versionName: String, versionCode: Int) {
        val userId = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO users (id, phone_number, full_name, role) VALUES (?, ?, ?, 'PLATFORM_ADMIN')",
        ).apply {
            setObject(1, userId)
            setString(2, "+1555" + versionCode.toString().padStart(7, '0'))
            setString(3, "Legacy Seed Admin")
        }.executeUpdate()

        connection.prepareStatement(
            """
            INSERT INTO app_releases (
                id, application_id, version_name, version_code, min_supported_version_code, is_mandatory,
                status, download_url, sha256, file_size_bytes, release_date, is_active, created_by
            ) VALUES (?, ?, ?, ?, 1, false, 'DRAFT', ?, ?, 1, ?, true, ?)
            """.trimIndent(),
        ).apply {
            setObject(1, UUID.randomUUID())
            setString(2, applicationId)
            setString(3, versionName)
            setInt(4, versionCode)
            setString(5, "https://rojanai.ir/download/legacy/$versionName.bin")
            setString(6, "e".repeat(64))
            setObject(7, LocalDate.of(2026, 1, 1))
            setObject(8, userId)
        }.executeUpdate()
    }
}
