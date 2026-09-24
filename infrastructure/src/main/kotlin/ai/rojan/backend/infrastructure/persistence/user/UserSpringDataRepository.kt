package ai.rojan.backend.infrastructure.persistence.user

import ai.rojan.backend.domain.user.UserRole
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserSpringDataRepository : JpaRepository<UserJpaEntity, UUID> {
    fun findByEmail(email: String): UserJpaEntity?
    fun existsByEmail(email: String): Boolean

    /** Mobile-First Authentication Phase 1. */
    fun findByPhoneNumber(phoneNumber: String): UserJpaEntity?
    fun existsByPhoneNumber(phoneNumber: String): Boolean

    /** Platform Roles. */
    fun findByRole(role: UserRole): List<UserJpaEntity>

    /**
     * Platform Management API Contract (Manager/Customer): role-only paginated listing, used when
     * no search term is supplied. A plain derived query with no `LOWER()`/`LIKE` expression at all -
     * deliberately separate from [findByRoleAndSearch] rather than folding search in as an
     * `OR (:search IS NULL OR ...)` clause, which previously bound SQL `NULL` into a
     * `LOWER()`/`CONCAT()` expression and hit a real, reproducible PostgreSQL/Hibernate parameter-type-
     * inference failure (`ERROR: function lower(bytea) does not exist`) on a query plan that hadn't
     * already been warmed by a prior non-null bind - confirmed live via a real browser E2E test
     * against a freshly booted backend. [UserRepositoryAdapter] dispatches here whenever the
     * normalized search is blank, mirroring [ai.rojan.backend.infrastructure.persistence.salon.SalonRepositoryAdapter.findAllActive]'s
     * own established two-method dispatch shape for the identical class of problem.
     */
    fun findByRole(role: UserRole, pageable: Pageable): Page<UserJpaEntity>

    /**
     * Platform Management API Contract (Manager/Customer): role + search paginated listing.
     * [search] is always a real, non-blank string here - [UserRepositoryAdapter] only ever calls this
     * overload once it has confirmed the normalized search is non-null, so `NULL` never reaches
     * `LOWER()`/`CONCAT()` in this query either, closing the same failure mode from the other side.
     */
    @Query(
        "SELECT u FROM UserJpaEntity u WHERE u.role = :role AND (" +
            "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR u.phoneNumber LIKE CONCAT('%', :search, '%'))",
    )
    fun findByRoleAndSearch(@Param("role") role: UserRole, @Param("search") search: String, pageable: Pageable): Page<UserJpaEntity>
}
