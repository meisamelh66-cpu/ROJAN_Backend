package ai.rojan.backend.infrastructure.persistence.customer

import ai.rojan.backend.domain.customer.CustomerStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface CustomerSpringDataRepository : JpaRepository<CustomerJpaEntity, UUID> {

    /**
     * Every filter is optional (`:param IS NULL OR ...`) rather than one
     * finder method per filter combination - three independent optional
     * filters would need 8 separate methods (see `BookingSpringDataRepository`'s
     * simpler two-filter-combination precedent, which does use separate
     * methods; this one JPQL query is the more scalable choice once a third
     * filter joins in). The tag filter is a subquery against
     * [CustomerTagJpaEntity] rather than a JPA `@OneToMany` association -
     * this codebase deliberately keeps every entity a flat table mapping
     * with no ORM-managed relationships (see every other `*JpaEntity` in
     * this module), joins/subqueries are written explicitly in the query
     * instead.
     *
     * [searchPattern] is already `%`-wrapped *and* lowercased (by
     * [CustomerRepositoryAdapter], not here), and `LOWER(...)` below is only
     * ever applied to the (properly `varchar`-typed) columns, never to the
     * bind parameter itself. Both concatenating `'%' || :search || '%'` in
     * JPQL, and even a bare `LOWER(:search)` with no concatenation at all,
     * each independently hit a genuine Postgres/JDBC prepared-statement bug
     * here: with a nullable bind parameter's type otherwise unresolvable
     * from context (no directly-compared typed column in the same
     * expression), Postgres inferred it as `bytea` rather than `text`, so
     * `LOWER(...)` failed at runtime with "function lower(bytea) does not
     * exist" (caught by this module's own integration test, not by
     * compilation - JPQL is not statically checked against the real
     * database). Never wrapping the parameter in a function - only the
     * column - sidesteps the ambiguity entirely.
     */
    @Query(
        """
        SELECT c FROM CustomerJpaEntity c
        WHERE c.salonId = :salonId
        AND (:status IS NULL OR c.status = :status)
        AND (:tag IS NULL OR c.id IN (SELECT t.customerId FROM CustomerTagJpaEntity t WHERE t.label = :tag))
        AND (
            :searchPattern IS NULL
            OR LOWER(c.fullName) LIKE CAST(:searchPattern AS string)
            OR (c.phoneNumber IS NOT NULL AND LOWER(c.phoneNumber) LIKE CAST(:searchPattern AS string))
            OR (c.email IS NOT NULL AND LOWER(c.email) LIKE CAST(:searchPattern AS string))
        )
        """,
    )
    fun findBySalonId(
        @Param("salonId") salonId: UUID,
        @Param("status") status: CustomerStatus?,
        @Param("tag") tag: String?,
        @Param("searchPattern") searchPattern: String?,
        pageable: Pageable,
    ): Page<CustomerJpaEntity>

    fun existsBySalonIdAndPhoneNumber(salonId: UUID, phoneNumber: String): Boolean
}
