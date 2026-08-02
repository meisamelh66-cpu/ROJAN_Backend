package ai.rojan.backend.domain.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaginationTest {

    @Test
    fun `accepts a valid page and size`() {
        val request = PageRequest(page = 0, size = 20)
        assertEquals(0, request.page)
        assertEquals(20, request.size)
    }

    @Test
    fun `rejects a negative page`() {
        assertThrows(IllegalArgumentException::class.java) { PageRequest(page = -1, size = 20) }
    }

    @Test
    fun `rejects a non-positive size`() {
        assertThrows(IllegalArgumentException::class.java) { PageRequest(page = 0, size = 0) }
    }

    @Test
    fun `rejects a size beyond the maximum`() {
        assertThrows(IllegalArgumentException::class.java) { PageRequest(page = 0, size = PageRequest.MAX_SIZE + 1) }
    }

    @Test
    fun `accepts the maximum size`() {
        PageRequest(page = 0, size = PageRequest.MAX_SIZE)
    }

    @Test
    fun `totalPages rounds up when elements do not divide evenly`() {
        val result = PageResult(content = emptyList<String>(), page = 0, size = 20, totalElements = 45)
        assertEquals(3, result.totalPages)
    }

    @Test
    fun `totalPages is exact when elements divide evenly`() {
        val result = PageResult(content = emptyList<String>(), page = 0, size = 20, totalElements = 40)
        assertEquals(2, result.totalPages)
    }

    @Test
    fun `totalPages is zero when there are no elements`() {
        val result = PageResult(content = emptyList<String>(), page = 0, size = 20, totalElements = 0)
        assertEquals(0, result.totalPages)
    }
}
