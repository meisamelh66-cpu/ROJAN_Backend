package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.Branch
import ai.rojan.backend.domain.salon.BranchId
import ai.rojan.backend.domain.salon.BranchRepository
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonFavorite
import ai.rojan.backend.domain.salon.SalonFavoriteId
import ai.rojan.backend.domain.salon.SalonFavoriteRepository
import ai.rojan.backend.domain.salon.SalonFollow
import ai.rojan.backend.domain.salon.SalonFollowId
import ai.rojan.backend.domain.salon.SalonFollowRepository
import ai.rojan.backend.domain.salon.SalonFollowStatus
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonInvite
import ai.rojan.backend.domain.salon.SalonInviteId
import ai.rojan.backend.domain.salon.SalonInviteRepository
import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.salon.SalonMembership
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.salon.SpecialistService
import ai.rojan.backend.domain.salon.SpecialistServiceRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import java.time.Instant

/** Shared in-memory fakes for the salon-management use case tests, mirroring the auth module's fake-repository style. */
internal class InMemorySalonRepository : SalonRepository {
    private val store = mutableMapOf<SalonId, Salon>()
    override fun save(salon: Salon): Salon = salon.also { store[it.id] = it }
    override fun findById(id: SalonId): Salon? = store[id]
    override fun findByOwnerId(ownerId: UserId): List<Salon> = store.values.filter { it.ownerId == ownerId }
    override fun findBySlug(slug: String): Salon? = store.values.find { it.slug == slug }
    override fun existsBySlug(slug: String): Boolean = store.values.any { it.slug == slug }

    override fun findAllActive(pageRequest: PageRequest, nameFilter: String?, sortDirection: SortDirection): PageResult<Salon> {
        val filtered = store.values
            .filter { it.active }
            .filter { nameFilter.isNullOrBlank() || it.name.contains(nameFilter, ignoreCase = true) }
            .sortedBy { it.name }
            .let { if (sortDirection == SortDirection.DESC) it.reversed() else it }
        val fromIndex = (pageRequest.page * pageRequest.size).coerceAtMost(filtered.size)
        val toIndex = (fromIndex + pageRequest.size).coerceAtMost(filtered.size)
        return PageResult(
            content = filtered.subList(fromIndex, toIndex),
            page = pageRequest.page,
            size = pageRequest.size,
            totalElements = filtered.size.toLong(),
        )
    }
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
    override fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): Specialist? =
        store.values.find { it.salonId == salonId && it.userId == userId }
}

internal class InMemorySalonMembershipRepository : SalonMembershipRepository {
    private val store = mutableMapOf<Pair<SalonId, UserId>, SalonMembership>()

    override fun assign(salonId: SalonId, userId: UserId, role: SalonRole): SalonMembership {
        val existing = store[salonId to userId]
        if (existing != null) {
            existing.changeRole(role)
            return existing
        }
        val membership = SalonMembership.create(salonId, userId, role)
        store[salonId to userId] = membership
        return membership
    }

    override fun remove(salonId: SalonId, userId: UserId) {
        store.remove(salonId to userId)
    }

    override fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): SalonMembership? = store[salonId to userId]

    override fun findBySalonId(salonId: SalonId): List<SalonMembership> = store.values.filter { it.salonId == salonId }
}

internal class InMemorySpecialistServiceRepository : SpecialistServiceRepository {
    private val store = mutableMapOf<Pair<SpecialistId, ServiceId>, SpecialistService>()

    override fun assign(specialistId: SpecialistId, serviceId: ServiceId): SpecialistService =
        store.getOrPut(specialistId to serviceId) { SpecialistService.create(specialistId, serviceId) }

    override fun remove(specialistId: SpecialistId, serviceId: ServiceId) {
        store.remove(specialistId to serviceId)
    }

    override fun findServiceIdsBySpecialistId(specialistId: SpecialistId): Set<ServiceId> =
        store.values.filter { it.specialistId == specialistId }.map { it.serviceId }.toSet()
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
    override fun findByPhoneNumber(phoneNumber: PhoneNumber): User? = store.values.find { it.phoneNumber == phoneNumber }
    override fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean = store.values.any { it.phoneNumber == phoneNumber }
}

internal class InMemorySalonInviteRepository : SalonInviteRepository {
    private val store = mutableMapOf<SalonInviteId, SalonInvite>()

    override fun save(invite: SalonInvite): SalonInvite = invite.also { store[it.id] = it }
    override fun findById(id: SalonInviteId): SalonInvite? = store[id]
    override fun findByToken(token: String): SalonInvite? = store.values.find { it.token == token }
    override fun findBySalonId(salonId: SalonId): List<SalonInvite> = store.values.filter { it.salonId == salonId }

    override fun acceptIfAvailable(token: String, acceptedBy: UserId, now: Instant): SalonInvite? {
        val invite = store.values.find { it.token == token } ?: return null
        if (invite.currentStatus(now) != SalonInviteStatus.CREATED) return null
        invite.accept(acceptedBy, now)
        return invite
    }
}

internal class InMemorySalonFollowRepository : SalonFollowRepository {
    private val store = mutableMapOf<SalonFollowId, SalonFollow>()

    override fun save(follow: SalonFollow): SalonFollow = follow.also { store[it.id] = it }

    override fun findByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId): SalonFollow? =
        store.values.find { it.customerId == customerId && it.salonId == salonId }

    override fun findActiveByCustomerId(customerId: UserId, pageRequest: PageRequest): PageResult<SalonFollow> {
        val filtered = store.values
            .filter { it.customerId == customerId && it.status == SalonFollowStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
        val fromIndex = (pageRequest.page * pageRequest.size).coerceAtMost(filtered.size)
        val toIndex = (fromIndex + pageRequest.size).coerceAtMost(filtered.size)
        return PageResult(
            content = filtered.subList(fromIndex, toIndex),
            page = pageRequest.page,
            size = pageRequest.size,
            totalElements = filtered.size.toLong(),
        )
    }
}

internal class InMemorySalonFavoriteRepository : SalonFavoriteRepository {
    private val store = mutableMapOf<SalonFavoriteId, SalonFavorite>()

    override fun save(favorite: SalonFavorite): SalonFavorite = favorite.also { store[it.id] = it }

    override fun findByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId): SalonFavorite? =
        store.values.find { it.customerId == customerId && it.salonId == salonId }

    override fun deleteByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId) {
        val match = store.values.find { it.customerId == customerId && it.salonId == salonId } ?: return
        store.remove(match.id)
    }

    override fun findByCustomerId(customerId: UserId, pageRequest: PageRequest): PageResult<SalonFavorite> {
        val filtered = store.values
            .filter { it.customerId == customerId }
            .sortedByDescending { it.createdAt }
        val fromIndex = (pageRequest.page * pageRequest.size).coerceAtMost(filtered.size)
        val toIndex = (fromIndex + pageRequest.size).coerceAtMost(filtered.size)
        return PageResult(
            content = filtered.subList(fromIndex, toIndex),
            page = pageRequest.page,
            size = pageRequest.size,
            totalElements = filtered.size.toLong(),
        )
    }
}
