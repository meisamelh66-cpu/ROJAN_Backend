package ai.rojan.backend.infrastructure.persistence.user

import ai.rojan.backend.domain.auth.PhoneNumber
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
                email = user.email?.value
                passwordHash = user.passwordHash
                phoneNumber = user.phoneNumber?.value
                fullName = user.fullName
                role = user.role
                active = user.active
            }
            ?: UserJpaEntity(
                id = user.id.value,
                email = user.email?.value,
                passwordHash = user.passwordHash,
                phoneNumber = user.phoneNumber?.value,
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

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): User? =
        jpaRepository.findByPhoneNumber(phoneNumber.value)?.toDomain()

    override fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean =
        jpaRepository.existsByPhoneNumber(phoneNumber.value)

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
    )
}
