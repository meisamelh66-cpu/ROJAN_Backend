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
 * App Release Update-Check Fix (V52): proves the migration's own backfill in isolation. A
 * `@SpringBootTest` always runs every migration (V52 included) before the Spring context - and
 * therefore the test - ever starts, so it can never observe a pre-V52 row. This test drives
 * Flyway's own Java API directly against a disposable embedded Postgres instead: migrate only
 * through V51, insert raw rows shaped exactly like real pre-existing production rows (no
 * client_version_code column yet), then migrate to latest (V52) and assert every one backfilled to
 * client_version_code == version_code - the correct value for every row that existed before this
 * column did (true for every app today except the one already-known, already-live Manager
 * 1.0.6/versionCode=7 row, which is corrected separately via an explicit UpdateAppRelease call, not
 * by this migration).
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

        val legacyRows = listOf(
            // Manager's own real incident shape: version_code=7, no client_version_code column yet
            // at the time this row would have been inserted in production.
            Triple("ai.rojan.designlab.manager", "1.0.6", 7),
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
                connection.prepareStatement(
                    "SELECT client_version_code FROM app_releases WHERE application_id = ? AND version_code = ?",
                ).apply {
                    setString(1, applicationId)
                    setInt(2, versionCode)
                }.executeQuery().use { rs ->
                    assertTrue(rs.next(), "expected a row for $applicationId / $versionCode")
                    assertEquals(
                        versionCode,
                        rs.getInt("client_version_code"),
                        "$applicationId's pre-existing row must backfill client_version_code to its own version_code",
                    )
                }
            }
        }
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
