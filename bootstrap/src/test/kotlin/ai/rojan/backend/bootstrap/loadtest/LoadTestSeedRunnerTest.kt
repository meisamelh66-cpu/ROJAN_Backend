package ai.rojan.backend.bootstrap.loadtest

import ai.rojan.backend.application.auth.RegisterUserUseCase
import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.findAnnotation
import org.springframework.context.annotation.Profile

private class InMemoryUserRepository : UserRepository {
    private val byEmail = linkedMapOf<String, User>()
    override fun save(user: User): User {
        byEmail[user.email.value] = user
        return user
    }
    override fun findById(id: UserId): User? = byEmail.values.find { it.id == id }
    override fun findByEmail(email: Email): User? = byEmail[email.value]
    override fun existsByEmail(email: Email): Boolean = byEmail.containsKey(email.value)
}

private object PassthroughPasswordEncoder : PasswordEncoderPort {
    override fun encode(rawPassword: String): String = "hashed:$rawPassword"
    override fun matches(rawPassword: String, encodedPassword: String): Boolean = encodedPassword == "hashed:$rawPassword"
}

/** Records every call; lets a test simulate a failure at a chosen manager index without a real database. */
private class FakeManagerUnitSeeder(private val failAtIndex: Int? = null) : ManagerUnitSeeding {
    val seededIndexes = mutableListOf<Int>()
    override fun seed(index: Int, email: String, password: String) {
        if (index == failAtIndex) error("simulated failure for manager unit #$index")
        seededIndexes += index
    }
}

/**
 * PR-02: proves the profile + confirmation double-guard and the core
 * orchestration behaviour (skip-if-already-seeded, stop-on-failure, never
 * seed customers after a manager-unit failure) without touching a real
 * database or activating `load-test-seed` against anything real.
 */
class LoadTestSeedRunnerTest {

    private val userRepository = InMemoryUserRepository()
    private val registerUserUseCase = RegisterUserUseCase(userRepository, PassthroughPasswordEncoder)

    private fun runner(confirmed: Boolean, seeder: FakeManagerUnitSeeder) =
        LoadTestSeedRunner(registerUserUseCase, seeder, userRepository, confirmed)

    @Test
    fun `both LoadTestSeedRunner and ManagerUnitSeeder declare the exact load-test-seed profile`() {
        val runnerProfile = LoadTestSeedRunner::class.findAnnotation<Profile>()
        val seederProfile = ManagerUnitSeeder::class.findAnnotation<Profile>()

        assertEquals(listOf("load-test-seed"), runnerProfile?.value?.toList())
        assertEquals(listOf("load-test-seed"), seederProfile?.value?.toList())
    }

    @Test
    fun `without confirmation, the runner seeds nothing even though the profile would be active`() {
        val seeder = FakeManagerUnitSeeder()

        runner(confirmed = false, seeder = seeder).run(emptyArray())

        assertTrue(seeder.seededIndexes.isEmpty(), "expected zero manager units seeded, got ${seeder.seededIndexes}")
        assertFalse(userRepository.existsByEmail(Email(LoadTestSeedRunner.customerEmail(1))))
    }

    @Test
    fun `with confirmation, every manager and customer index is seeded exactly once`() {
        val seeder = FakeManagerUnitSeeder()

        runner(confirmed = true, seeder = seeder).run(emptyArray())

        assertEquals((1..LoadTestSeedRunner.MANAGER_COUNT).toList(), seeder.seededIndexes)
        assertTrue(userRepository.existsByEmail(Email(LoadTestSeedRunner.customerEmail(1))))
        assertTrue(userRepository.existsByEmail(Email(LoadTestSeedRunner.customerEmail(LoadTestSeedRunner.CUSTOMER_COUNT))))
    }

    @Test
    fun `a manager whose deterministic email already exists is skipped, not recreated`() {
        userRepository.save(
            User.register(
                email = Email(LoadTestSeedRunner.managerEmail(3)),
                passwordHash = "existing",
                fullName = "Already Seeded",
                role = ai.rojan.backend.domain.user.UserRole.MANAGER,
            ),
        )
        val seeder = FakeManagerUnitSeeder()

        runner(confirmed = true, seeder = seeder).run(emptyArray())

        assertFalse(seeder.seededIndexes.contains(3), "manager #3 already existed and must not be re-seeded")
        assertEquals(LoadTestSeedRunner.MANAGER_COUNT - 1, seeder.seededIndexes.size)
    }

    @Test
    fun `a failure partway through manager seeding stops immediately and skips all customer seeding`() {
        val seeder = FakeManagerUnitSeeder(failAtIndex = 5)

        runner(confirmed = true, seeder = seeder).run(emptyArray())

        assertEquals(listOf(1, 2, 3, 4), seeder.seededIndexes, "must stop exactly at the failing unit, never continue past it")
        assertFalse(userRepository.existsByEmail(Email(LoadTestSeedRunner.customerEmail(1))), "customers must never be seeded after a manager-unit failure")
    }
}
