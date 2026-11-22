package ai.rojan.backend.infrastructure.persistence.auth

import org.springframework.data.jpa.repository.JpaRepository

interface ReviewerAccessGrantSpringDataRepository : JpaRepository<ReviewerAccessGrantJpaEntity, String>
