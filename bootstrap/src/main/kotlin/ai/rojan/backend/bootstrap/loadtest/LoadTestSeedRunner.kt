package ai.rojan.backend.bootstrap.loadtest

import ai.rojan.backend.application.auth.RegisterUserCommand
import ai.rojan.backend.application.auth.RegisterUserUseCase
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * PR-02 (Load Test Plan v2) seed orchestrator.
 *
 * Inactive unless BOTH are true:
 * 1. `SPRING_PROFILES_ACTIVE` includes `load-test-seed` ([Profile] below) -
 *    never included in any real deployment profile (`prod`) or test profile
 *    (`test`) anywhere in this repository.
 * 2. `rojan.load-test-seed.confirm=true` is explicitly set - a second,
 *    independent guard so the profile name alone is never sufficient to run
 *    bulk account/salon creation.
 *
 * Idempotent by construction, not by a dedicated ledger: every seeded
 * account uses a fixed, deterministic email (`pr02-manager-NNNN@loadtest.local`,
 * `pr02-customer-NNNN@loadtest.local`). [RegisterUserUseCase]'s own,
 * already-enforced email-uniqueness check is the resumability signal - a
 * re-run (or a run after a prior partial failure) skips any unit whose
 * manager/customer email already exists. Each manager's full chain
 * (register -> salon -> services -> working hours -> specialists ->
 * availability) runs inside [ManagerUnitSeeder.seed]'s own `@Transactional`
 * boundary, so a mid-chain failure leaves that one unit fully rolled back -
 * the email-existence check therefore never has to distinguish "fully done"
 * from "half done" for a given index.
 *
 * Does not itself create any "booking slot" row - none exists
 * ([ai.rojan.backend.domain.booking.TimeSlotEngine] computes slots
 * on demand from the working-hours/availability rows this runner seeds).
 * Runs once; does not stop the process afterward (ordinary
 * [CommandLineRunner] semantics) - this application keeps serving HTTP
 * exactly as it would with zero runners.
 */
@Component
@Profile("load-test-seed")
class LoadTestSeedRunner(
    private val registerUserUseCase: RegisterUserUseCase,
    private val managerUnitSeeder: ManagerUnitSeeding,
    private val userRepository: UserRepository,
    @Value("\${rojan.load-test-seed.confirm:false}") private val confirmed: Boolean,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(LoadTestSeedRunner::class.java)

    override fun run(args: Array<String>) {
        if (!confirmed) {
            log.warn(
                "PR-02 load-test-seed profile is active but rojan.load-test-seed.confirm is not " +
                    "true - seeding SKIPPED. Set it explicitly to proceed.",
            )
            return
        }

        log.info(
            "PR-02 load-test seed starting: target {} managers/salons, {} specialists, {} services, {} customers.",
            MANAGER_COUNT,
            MANAGER_COUNT * ManagerUnitSeeder.SPECIALISTS_PER_SALON,
            MANAGER_COUNT * ManagerUnitSeeder.SERVICES_PER_SALON,
            CUSTOMER_COUNT,
        )

        var managersCreated = 0
        var managersSkipped = 0
        var failedAt: Int? = null

        for (i in 1..MANAGER_COUNT) {
            val email = managerEmail(i)
            if (userRepository.existsByEmail(Email(email))) {
                managersSkipped++
                continue
            }
            try {
                managerUnitSeeder.seed(index = i, email = email, password = SEED_PASSWORD)
                managersCreated++
            } catch (ex: Exception) {
                failedAt = i
                log.error("PR-02 seed FAILED at manager unit #{} - stopping, no further units attempted. Cause: {}", i, ex.message, ex)
                break
            }
        }

        var customersCreated = 0
        var customersSkipped = 0
        if (failedAt == null) {
            for (i in 1..CUSTOMER_COUNT) {
                val email = customerEmail(i)
                if (userRepository.existsByEmail(Email(email))) {
                    customersSkipped++
                    continue
                }
                registerUserUseCase.execute(
                    RegisterUserCommand(email = email, rawPassword = SEED_PASSWORD, fullName = "PR02 Customer $i", role = UserRole.CUSTOMER),
                )
                customersCreated++
            }
        } else {
            log.warn("Skipping customer seeding because manager seeding failed at unit #{}.", failedAt)
        }

        log.info(
            "PR-02 load-test seed finished. Managers: {} created, {} skipped. Customers: {} created, {} skipped. Failed unit: {}.",
            managersCreated,
            managersSkipped,
            customersCreated,
            customersSkipped,
            failedAt ?: "none",
        )
    }

    companion object {
        const val MANAGER_COUNT = 150
        const val CUSTOMER_COUNT = 600

        // Deliberately not logged anywhere - see the class doc comment's
        // "no sensitive credentials" requirement. Only ever used by this
        // disposable, never-production seed path.
        private const val SEED_PASSWORD = "Pr02LoadTestSeed!2026"

        fun managerEmail(index: Int): String = "pr02-manager-%04d@loadtest.local".format(index)
        fun customerEmail(index: Int): String = "pr02-customer-%04d@loadtest.local".format(index)
    }
}
