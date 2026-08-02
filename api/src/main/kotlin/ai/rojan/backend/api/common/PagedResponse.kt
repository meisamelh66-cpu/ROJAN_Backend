package ai.rojan.backend.api.common

import ai.rojan.backend.domain.common.PageResult

/** Common paginated-list envelope returned by every browse/list endpoint that supports pagination. */
data class PagedResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun <T, R> PageResult<T>.toPagedResponse(mapper: (T) -> R): PagedResponse<R> =
    PagedResponse(
        content = content.map(mapper),
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
    )
