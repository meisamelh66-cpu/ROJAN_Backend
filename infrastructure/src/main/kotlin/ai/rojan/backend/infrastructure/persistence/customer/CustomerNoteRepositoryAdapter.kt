package ai.rojan.backend.infrastructure.persistence.customer

import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerNote
import ai.rojan.backend.domain.customer.CustomerNoteId
import ai.rojan.backend.domain.customer.CustomerNoteRepository
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class CustomerNoteRepositoryAdapter(
    private val jpaRepository: CustomerNoteSpringDataRepository,
) : CustomerNoteRepository {

    override fun save(note: CustomerNote): CustomerNote {
        val entity = CustomerNoteJpaEntity(
            id = note.id.value,
            customerId = note.customerId.value,
            authorId = note.authorId.value,
            text = note.text,
        )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByCustomerId(customerId: CustomerId): List<CustomerNote> =
        jpaRepository.findByCustomerId(customerId.value).map { it.toDomain() }

    private fun CustomerNoteJpaEntity.toDomain(): CustomerNote = CustomerNote(
        id = CustomerNoteId(id),
        customerId = CustomerId(customerId),
        authorId = UserId(authorId),
        text = text,
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
