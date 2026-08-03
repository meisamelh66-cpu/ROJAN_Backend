package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.Branch
import ai.rojan.backend.domain.salon.BranchId
import ai.rojan.backend.domain.salon.BranchRepository
import ai.rojan.backend.domain.salon.SalonId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class BranchRepositoryAdapter(
    private val jpaRepository: BranchSpringDataRepository,
) : BranchRepository {

    override fun save(branch: Branch): Branch {
        val entity = jpaRepository.findById(branch.id.value).orElse(null)
            ?.apply {
                name = branch.name
                address = branch.address
                phone = branch.phone
                active = branch.active
            }
            ?: BranchJpaEntity(
                id = branch.id.value,
                salonId = branch.salonId.value,
                name = branch.name,
                address = branch.address,
                phone = branch.phone,
                active = branch.active,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: BranchId): Branch? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<Branch> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    private fun BranchJpaEntity.toDomain(): Branch = Branch.reconstitute(
        id = BranchId(id),
        salonId = SalonId(salonId),
        name = name,
        address = address,
        phone = phone,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
