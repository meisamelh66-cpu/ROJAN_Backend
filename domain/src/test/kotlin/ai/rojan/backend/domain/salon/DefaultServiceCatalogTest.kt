package ai.rojan.backend.domain.salon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultServiceCatalogTest {

    @Test
    fun `ships exactly the 15 approved default categories`() {
        assertEquals(15, DefaultServiceCatalog.entries.size)
    }

    @Test
    fun `every category has a non-blank name and at least one suggested service type`() {
        DefaultServiceCatalog.entries.forEach { entry ->
            assertTrue(entry.name.isNotBlank(), "category name must not be blank")
            assertTrue(entry.serviceTypes.isNotEmpty(), "${entry.name} must have at least one service type")
            entry.serviceTypes.forEach { assertTrue(it.isNotBlank(), "${entry.name} has a blank service type") }
        }
    }

    @Test
    fun `no two default categories normalize to the same name`() {
        val normalized = DefaultServiceCatalog.entries.map { ServiceCategoryNameNormalizer.normalize(it.name) }
        assertEquals(normalized.size, normalized.toSet().size)
    }
}
