package ai.rojan.backend.domain.media

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private fun newAsset(mediaType: MediaType = MediaType.LOGO) = MediaAsset.create(
    salonId = SalonId.new(),
    mediaType = mediaType,
    storageKey = "salons/x/media/y",
    originalName = "logo.png",
    mimeType = "image/png",
    fileSize = 1024,
    uploadedBy = UserId.new(),
)

class MediaAssetTest {

    @Test
    fun `create defaults a new asset to active`() {
        val asset = newAsset()
        assertEquals(MediaAssetStatus.ACTIVE, asset.status)
    }

    @Test
    fun `create rejects a blank storage key`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaAsset.create(SalonId.new(), MediaType.LOGO, "  ", "logo.png", "image/png", 1024, UserId.new())
        }
    }

    @Test
    fun `create rejects a non-positive file size`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaAsset.create(SalonId.new(), MediaType.LOGO, "key", "logo.png", "image/png", 0, UserId.new())
        }
    }

    @Test
    fun `archive transitions an active asset out of the current slot`() {
        val asset = newAsset()
        asset.archive()
        assertEquals(MediaAssetStatus.ARCHIVED, asset.status)
    }

    @Test
    fun `delete is idempotent - deleting an already-deleted asset does not throw`() {
        val asset = newAsset()
        asset.delete()
        asset.delete()
        assertEquals(MediaAssetStatus.DELETED, asset.status)
    }

    @Test
    fun `activate rejects a deleted asset`() {
        val asset = newAsset()
        asset.delete()
        assertThrows(IllegalArgumentException::class.java) { asset.activate() }
    }

    @Test
    fun `archive rejects a deleted asset`() {
        val asset = newAsset()
        asset.delete()
        assertThrows(IllegalArgumentException::class.java) { asset.archive() }
    }
}
