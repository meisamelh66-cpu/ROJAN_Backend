package ai.rojan.backend.infrastructure.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises real file-system I/O against a JUnit-managed temp directory -
 * no mocking, the same "prove the real behavior, not a stand-in" approach
 * [ai.rojan.backend.infrastructure.sms.RealSmsProviderAdapterTest] uses
 * for its own real-adapter class.
 */
class LocalDiskMediaStorageAdapterTest {

    private fun adapter(root: Path, publicBaseUrl: String = "https://cdn.test.rojan.ai") =
        LocalDiskMediaStorageAdapter(LocalMediaStorageProperties(storageRoot = root.toString(), publicBaseUrl = publicBaseUrl))

    @Test
    fun `upload writes the real bytes under storageRoot, nested directories included`(@TempDir tempDir: Path) {
        val content = byteArrayOf(1, 2, 3, 4, 5)

        adapter(tempDir).upload("salons/abc/media/xyz", content, "image/png")

        val written = tempDir.resolve("salons/abc/media/xyz")
        assertTrue(Files.exists(written))
        assertArrayEquals(content, Files.readAllBytes(written))
    }

    @Test
    fun `upload overwrites an existing file at the same storageKey`(@TempDir tempDir: Path) {
        val a = adapter(tempDir)
        a.upload("salons/abc/media/xyz", byteArrayOf(1), "image/png")
        a.upload("salons/abc/media/xyz", byteArrayOf(9, 9, 9), "image/png")

        assertArrayEquals(byteArrayOf(9, 9, 9), Files.readAllBytes(tempDir.resolve("salons/abc/media/xyz")))
    }

    @Test
    fun `delete removes the file`(@TempDir tempDir: Path) {
        val a = adapter(tempDir)
        a.upload("salons/abc/media/xyz", byteArrayOf(1), "image/png")

        a.delete("salons/abc/media/xyz")

        assertFalse(Files.exists(tempDir.resolve("salons/abc/media/xyz")))
    }

    @Test
    fun `delete on a missing key is a no-op, not an error`(@TempDir tempDir: Path) {
        adapter(tempDir).delete("salons/abc/media/never-uploaded")
        // no exception - idempotent from the caller's perspective, matching MediaStoragePort's contract
    }

    @Test
    fun `resolveUrl builds a media-prefixed URL under the configured public base`(@TempDir tempDir: Path) {
        val url = adapter(tempDir, publicBaseUrl = "https://api.rojanai.ir").resolveUrl("salons/abc/media/xyz")

        assertEquals("https://api.rojanai.ir/media/salons/abc/media/xyz", url)
    }

    @Test
    fun `resolveUrl trims a trailing slash on the configured base before appending`(@TempDir tempDir: Path) {
        val url = adapter(tempDir, publicBaseUrl = "https://api.rojanai.ir/").resolveUrl("k")

        assertEquals("https://api.rojanai.ir/media/k", url)
    }

    @Test
    fun `resolveSignedUrl returns the same URL as resolveUrl, ignoring expiry`(@TempDir tempDir: Path) {
        val a = adapter(tempDir, publicBaseUrl = "https://api.rojanai.ir")

        assertEquals(a.resolveUrl("salons/abc/media/xyz"), a.resolveSignedUrl("salons/abc/media/xyz", expirySeconds = 60))
    }

    @Test
    fun `rejects a storageKey that would escape storageRoot via parent-directory traversal`(@TempDir tempDir: Path) {
        assertThrows(IllegalArgumentException::class.java) {
            adapter(tempDir).upload("../../etc/passwd", byteArrayOf(1), "image/png")
        }
    }

    @Test
    fun `rejects an absolute storageKey`(@TempDir tempDir: Path) {
        val absoluteElsewhere = tempDir.root.resolve("some-other-root").toString()
        assertThrows(IllegalArgumentException::class.java) {
            adapter(tempDir).upload(absoluteElsewhere, byteArrayOf(1), "image/png")
        }
    }
}
