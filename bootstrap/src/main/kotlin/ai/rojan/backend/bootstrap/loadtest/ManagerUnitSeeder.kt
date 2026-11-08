package ai.rojan.backend.bootstrap.loadtest

import ai.rojan.backend.application.auth.RegisterUserCommand
import ai.rojan.backend.application.auth.RegisterUserUseCase
import ai.rojan.backend.application.salon.CreateSalonCommand
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.CreateServiceCategoryCommand
import ai.rojan.backend.application.salon.CreateServiceCategoryUseCase
import ai.rojan.backend.application.salon.CreateServiceCommand
import ai.rojan.backend.application.salon.CreateServiceUseCase
import ai.rojan.backend.application.salon.CreateSpecialistCommand
import ai.rojan.backend.application.salon.CreateSpecialistUseCase
import ai.rojan.backend.application.schedule.SetSpecialistWeeklyAvailabilityUseCase
import ai.rojan.backend.application.schedule.SetWeeklyAvailabilityCommand
import ai.rojan.backend.application.schedule.SetWorkingHoursCommand
import ai.rojan.backend.application.schedule.SetWorkingHoursUseCase
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.user.UserRole
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime

/** Narrow seam so [LoadTestSeedRunner] can be unit-tested with a hand-written fake, without a real database. */
interface ManagerUnitSeeding {
    fun seed(index: Int, email: String, password: String)
}

/**
 * PR-02 load-test seed: one Manager's complete creation chain
 * (register -> salon -> service category -> services -> working hours ->
 * specialists -> specialist weekly availability), as a single transaction.
 *
 * Deliberately a separate bean from [LoadTestSeedRunner]: `@Transactional`
 * only takes effect on a real cross-bean (proxied) call - a self-invoked
 * method on the same instance would silently skip the proxy and run with
 * no transaction at all. Carries its own `@Profile` guard too, as a second,
 * independent layer - even if something else ever constructed this bean
 * directly, it still only exists under `load-test-seed`.
 */
@Component
@Profile("load-test-seed")
class ManagerUnitSeeder(
    private val registerUserUseCase: RegisterUserUseCase,
    private val createSalonUseCase: CreateSalonUseCase,
    private val createServiceCategoryUseCase: CreateServiceCategoryUseCase,
    private val createServiceUseCase: CreateServiceUseCase,
    private val createSpecialistUseCase: CreateSpecialistUseCase,
    private val setWorkingHoursUseCase: SetWorkingHoursUseCase,
    private val setSpecialistWeeklyAvailabilityUseCase: SetSpecialistWeeklyAvailabilityUseCase,
) : ManagerUnitSeeding {

    @Transactional
    override fun seed(index: Int, email: String, password: String) {
        val manager = registerUserUseCase.execute(
            RegisterUserCommand(email = email, rawPassword = password, fullName = "PR02 Manager $index", role = UserRole.MANAGER),
        )

        val salon = createSalonUseCase.execute(
            CreateSalonCommand(
                ownerId = manager.id,
                name = "PR02 Seed Salon $index",
                description = null,
                phone = "+98900%07d".format(index),
                email = null,
                address = "PR02 Seed Address $index",
            ),
        )

        val category = createServiceCategoryUseCase.execute(
            CreateServiceCategoryCommand(salonId = salon.id, callerId = manager.id, name = "PR02 Services", description = null),
        )

        repeat(SERVICES_PER_SALON) { s ->
            createServiceUseCase.execute(
                CreateServiceCommand(
                    salonId = salon.id,
                    categoryId = category.id,
                    callerId = manager.id,
                    name = "PR02 Service $index-${s + 1}",
                    description = null,
                    durationMinutes = SERVICE_DURATION_MINUTES,
                    price = SERVICE_PRICE,
                ),
            )
        }

        FULL_WEEK.forEach { day ->
            setWorkingHoursUseCase.execute(
                SetWorkingHoursCommand(salonId = salon.id, callerId = manager.id, dayOfWeek = day, intervals = listOf(BUSINESS_HOURS)),
            )
        }

        repeat(SPECIALISTS_PER_SALON) { sp ->
            val specialist = createSpecialistUseCase.execute(
                CreateSpecialistCommand(
                    salonId = salon.id,
                    callerId = manager.id,
                    userId = null,
                    displayName = "PR02 Specialist $index-${sp + 1}",
                    bio = null,
                    photoUrl = null,
                ),
            )
            FULL_WEEK.forEach { day ->
                setSpecialistWeeklyAvailabilityUseCase.execute(
                    SetWeeklyAvailabilityCommand(specialistId = specialist.id, callerId = manager.id, dayOfWeek = day, intervals = listOf(BUSINESS_HOURS)),
                )
            }
        }
    }

    companion object {
        const val SPECIALISTS_PER_SALON = 3
        const val SERVICES_PER_SALON = 5
        private const val SERVICE_DURATION_MINUTES = 30
        private val SERVICE_PRICE = BigDecimal("100000")
        val FULL_WEEK: List<DayOfWeek> = DayOfWeek.values().toList()
        val BUSINESS_HOURS = TimeInterval(LocalTime.of(9, 0), LocalTime.of(18, 0))
    }
}
