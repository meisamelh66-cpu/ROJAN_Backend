package ai.rojan.backend.domain.common

/** Sort direction for the single, curated sort key each paginated finder supports. */
enum class SortDirection {
    ASC,
    DESC,
}

/**
 * A framework-free pagination request. Kept independent of Spring Data's
 * `Pageable` so the domain layer stays free of web/persistence frameworks;
 * infrastructure adapters translate to/from `Pageable` at the boundary.
 */
data class PageRequest(val page: Int, val size: Int) {
    init {
        require(page >= 0) { "page must not be negative" }
        require(size in 1..MAX_SIZE) { "size must be between 1 and $MAX_SIZE" }
    }

    companion object {
        const val MAX_SIZE = 100
        const val DEFAULT_SIZE = 20
    }
}

data class PageResult<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    val totalPages: Int get() = if (totalElements == 0L) 0 else ((totalElements + size - 1) / size).toInt()
}
