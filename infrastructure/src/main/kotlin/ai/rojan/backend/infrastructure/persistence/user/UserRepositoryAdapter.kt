package ai.rojan.backend.infrastructure.persistence.user

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.domain.PageRequest as SpringPageRequest

/** Repository-pattern adapter: implements the domain [UserRepository] port on top of Spring Data JPA. */
@Repository
class UserRepositoryAdapter(
    private val jpaRepository: UserSpringDataRepository,
) : UserRepository {

    override fun save(user: User): User {
        val entity = jpaRepository.findById(user.id.value).orElse(null)
            ?.apply {
                email = user.email?.value
                passwordHash = user.passwordHash
                phoneNumber = user.phoneNumber?.value
                fullName = user.fullName
                role = user.role
                active = user.active
                avatarMediaId = user.avatarMediaId?.value
                coverMediaId = user.coverMediaId?.value
            }
            ?: UserJpaEntity(
                id = user.id.value,
                email = user.email?.value,
                passwordHash = user.passwordHash,
                phoneNumber = user.phoneNumber?.value,
                fullName = user.fullName,
                role = user.role,
                active = user.active,
                avatarMediaId = user.avatarMediaId?.value,
                coverMediaId = user.coverMediaId?.value,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: UserId): User? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByEmail(email: Email): User? =
        jpaRepository.findByEmail(email.value)?.toDomain()

    override fun existsByEmail(email: Email): Boolean =
        jpaRepository.existsByEmail(email.value)

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): User? =
        jpaRepository.findByPhoneNumber(phoneNumber.value)?.toDomain()

    override fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean =
        jpaRepository.existsByPhoneNumber(phoneNumber.value)

    override fun findByRole(role: UserRole): List<User> =
        jpaRepository.findByRole(role).map { it.toDomain() }

    override fun findByRole(role: UserRole, pageRequest: PageRequest, search: String?, sortDirection: SortDirection): PageResult<User> {
        val direction = if (sortDirection == SortDirection.ASC) Sort.Direction.ASC else Sort.Direction.DESC
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size, Sort.by(direction, "fullName"))
        // Dispatches to a genuinely different query when there's no search term, rather than
        // binding a null `search` into the same LOWER()/LIKE-shaped query - see
        // UserSpringDataRepository.findByRole(role, pageable)'s own doc comment for the real,
        // confirmed PostgreSQL/Hibernate failure this avoids. Same dispatch shape
        // SalonRepositoryAdapter.findAllActive already establishes for name-filtered browsing.
        val normalizedSearch = search?.trim()?.ifBlank { null }
        val page = if (normalizedSearch == null) {
            jpaRepository.findByRole(role, pageable)
        } else {
            jpaRepository.findByRoleAndSearch(role, normalizedSearch, pageable)
        }
        return PageResult(
            content = page.content.map { it.toDomain() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
        )
    }

    private fun UserJpaEntity.toDomain(): User = User.reconstitute(
        id = UserId(id),
        email = email?.let { Email(it) },
        passwordHash = passwordHash,
        phoneNumber = phoneNumber?.let { PhoneNumber(it) },
        fullName = fullName,
        role = role,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
        avatarMediaId = avatarMediaId?.let { MediaAssetId(it) },
        coverMediaId = coverMediaId?.let { MediaAssetId(it) },
    )
}
