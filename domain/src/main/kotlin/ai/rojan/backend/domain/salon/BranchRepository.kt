package ai.rojan.backend.domain.salon

interface BranchRepository {
    fun save(branch: Branch): Branch
    fun findById(id: BranchId): Branch?
    fun findBySalonId(salonId: SalonId): List<Branch>
}
