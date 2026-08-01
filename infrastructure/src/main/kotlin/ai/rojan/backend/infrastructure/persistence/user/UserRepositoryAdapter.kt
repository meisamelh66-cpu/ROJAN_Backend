package ai.rojan.backend.infrastructure.persistence.user

import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/** Repository-pattern adapter: implements the domain [UserRepository] port on top of Spring Data JPA. */
@Repository
class UserRepositoryAdapter(
    private val jpaRepository: UserSpringDataRepository,
) : UserRepository {

    override fun save(user: User): User {
        val entity = jpaRepository.findById(user.id.value).orElse(null)
            ?.apply {
                email = user.email.value
                passwordHash = user.passwordHash
                fullName = user.fullName
                role = user.role
                active = user.active
            }
            ?: UserJpaEntity(
                id = user.id.value,
                email = user.email.value,
                passwordHash = user.passwordHash,
                fullName = user.fullName,
                role = user.role,
                active = user.active,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: UserId): User? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByEmail(email: Email): User? =
        jpaRepository.findByEmail(email.value)?.toDomain()

    override fun existsByEmail(email: Email): Boolean =
        jpaRepository.existsByEmail(email.value)

    private fun UserJpaEntity.toDomain(): User = User.reconstitute(
        id = UserId(id),
        email = Email(email),
        passwordHash = passwordHash,
        fullName = fullName,
        role = role,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
