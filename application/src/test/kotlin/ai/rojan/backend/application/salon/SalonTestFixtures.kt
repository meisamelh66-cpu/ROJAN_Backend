package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.salon.Branch
import ai.rojan.backend.domain.salon.BranchId
import ai.rojan.backend.domain.salon.BranchRepository
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

/** Shared in-memory fakes for the salon-management use case tests, mirroring the auth module's fake-repository style. */
internal class InMemorySalonRepository : SalonRepository {
    private val store = mutableMapOf<SalonId, Salon>()
    override fun save(salon: Salon): Salon = salon.also { store[it.id] = it }
    override fun findById(id: SalonId): Salon? = store[id]
    override fun findByOwnerId(ownerId: UserId): List<Salon> = store.values.filter { it.ownerId == ownerId }
    override fun findAllActive(): List<Salon> = store.values.filter { it.active }
}

internal class InMemoryBranchRepository : BranchRepository {
    private val store = mutableMapOf<BranchId, Branch>()
    override fun save(branch: Branch): Branch = branch.also { store[it.id] = it }
    override fun findById(id: BranchId): Branch? = store[id]
    override fun findBySalonId(salonId: SalonId): List<Branch> = store.values.filter { it.salonId == salonId }
}

internal class InMemoryServiceCategoryRepository : ServiceCategoryRepository {
    private val store = mutableMapOf<ServiceCategoryId, ServiceCategory>()
    override fun save(category: ServiceCategory): ServiceCategory = category.also { store[it.id] = it }
    override fun findById(id: ServiceCategoryId): ServiceCategory? = store[id]
    override fun findBySalonId(salonId: SalonId): List<ServiceCategory> =
        store.values.filter { it.salonId == salonId }
}

internal class InMemoryServiceRepository : ServiceRepository {
    private val store = mutableMapOf<ServiceId, Service>()
    override fun save(service: Service): Service = service.also { store[it.id] = it }
    override fun findById(id: ServiceId): Service? = store[id]
    override fun findByCategoryId(categoryId: ServiceCategoryId): List<Service> =
        store.values.filter { it.categoryId == categoryId }
    override fun findBySalonId(salonId: SalonId): List<Service> = store.values.filter { it.salonId == salonId }
}

internal class InMemorySpecialistRepository : SpecialistRepository {
    private val store = mutableMapOf<SpecialistId, Specialist>()
    override fun save(specialist: Specialist): Specialist = specialist.also { store[it.id] = it }
    override fun findById(id: SpecialistId): Specialist? = store[id]
    override fun findBySalonId(salonId: SalonId): List<Specialist> = store.values.filter { it.salonId == salonId }
}

internal class InMemorySalonUserRepository : UserRepository {
    private val store = mutableMapOf<UserId, User>()
    fun register(user: User) {
        store[user.id] = user
    }
    override fun save(user: User): User = user.also { store[it.id] = it }
    override fun findById(id: UserId): User? = store[id]
    override fun findByEmail(email: Email): User? = store.values.find { it.email == email }
    override fun existsByEmail(email: Email): Boolean = store.values.any { it.email == email }
}
