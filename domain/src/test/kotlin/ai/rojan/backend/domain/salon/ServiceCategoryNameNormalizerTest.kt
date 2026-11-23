package ai.rojan.backend.domain.salon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ServiceCategoryNameNormalizerTest {

    @Test
    fun `treats Arabic-script letter variants as identical to their Persian equivalents`() {
        assertEquals(
            ServiceCategoryNameNormalizer.normalize("كاشت ناخن"),
            ServiceCategoryNameNormalizer.normalize("کاشت ناخن"),
        )
    }

    @Test
    fun `collapses extra internal spacing and a zero-width non-joiner`() {
        assertEquals(
            ServiceCategoryNameNormalizer.normalize("رنگ و مش"),
            ServiceCategoryNameNormalizer.normalize("رنگ  و   مش"),
        )
        assertEquals(
            ServiceCategoryNameNormalizer.normalize("رنگ و مش"),
            ServiceCategoryNameNormalizer.normalize("رنگ‌و‌مش"),
        )
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals(
            ServiceCategoryNameNormalizer.normalize("ناخن"),
            ServiceCategoryNameNormalizer.normalize("  ناخن  "),
        )
    }

    @Test
    fun `genuinely different names stay different`() {
        assertNotEquals(
            ServiceCategoryNameNormalizer.normalize("ناخن"),
            ServiceCategoryNameNormalizer.normalize("مژه"),
        )
    }
}
