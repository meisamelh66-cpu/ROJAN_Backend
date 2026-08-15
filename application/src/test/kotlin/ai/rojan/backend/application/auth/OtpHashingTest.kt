package ai.rojan.backend.application.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class OtpHashingTest {

    @Test
    fun `hashes identical for ASCII and Persian digits of the same code`() {
        assertEquals(OtpHashing.hash("482913"), OtpHashing.hash("۴۸۲۹۱۳"))
    }

    @Test
    fun `hashes identical for ASCII and Arabic-Indic digits of the same code`() {
        assertEquals(OtpHashing.hash("482913"), OtpHashing.hash("٤٨٢٩١٣"))
    }

    @Test
    fun `hashes identical for a code mixing ASCII and Persian digits`() {
        assertEquals(OtpHashing.hash("482913"), OtpHashing.hash("48۲۹13"))
    }

    @Test
    fun `still distinguishes genuinely different codes after normalization`() {
        assertNotEquals(OtpHashing.hash("482913"), OtpHashing.hash("482914"))
        assertNotEquals(OtpHashing.hash("482913"), OtpHashing.hash("۴۸۲۹۱۴"))
    }

    @Test
    fun `is deterministic for a plain ASCII code`() {
        assertEquals(OtpHashing.hash("482913"), OtpHashing.hash("482913"))
    }
}
