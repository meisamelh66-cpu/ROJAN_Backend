package ai.rojan.backend.application.banner

import ai.rojan.backend.application.media.InMemoryMediaStoragePort
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.banner.Banner
import ai.rojan.backend.domain.banner.BannerId
import ai.rojan.backend.domain.banner.BannerRepository
import ai.rojan.backend.domain.banner.BannerTarget
import ai.rojan.backend.domain.common.BannerNotFoundException
import ai.rojan.backend.domain.common.BannerReorderMismatchException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Mirrors [ai.rojan.backend.application.salon.InMemorySalonRepository]'s in-memory style, same as every other use-case test in this module. */
private class InMemoryBannerRepository : BannerRepository {
    private val store = mutableMapOf<BannerId, Banner>()
    override fun findById(id: BannerId): Banner? = store[id]
    override fun findByTarget(target: BannerTarget): List<Banner> =
        store.values.filter { it.target == target }.sortedBy { it.displayOrder }
    override fun findActiveByTarget(target: BannerTarget): List<Banner> =
        store.values.filter { it.target == target && it.isActive }.sortedBy { it.displayOrder }
    override fun save(banner: Banner): Banner = banner.also { store[it.id] = it }
    override fun delete(id: BannerId) {
        store.remove(id)
    }
}

class BannerUseCasesTest {

    private val REAL_PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(92)

    private val userRepository = InMemorySalonUserRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val bannerRepository = InMemoryBannerRepository()
    private val mediaStoragePort = InMemoryMediaStoragePort()

    private val uploadUseCase = UploadBannerUseCase(bannerRepository, mediaStoragePort, platformAuthorization)
    private val listUseCase = ListBannersForAdminUseCase(bannerRepository, platformAuthorization)
    private val listActiveUseCase = ListActiveBannersUseCase(bannerRepository)
    private val updateMetadataUseCase = UpdateBannerMetadataUseCase(bannerRepository, platformAuthorization)
    private val replaceImageUseCase = ReplaceBannerImageUseCase(bannerRepository, mediaStoragePort, platformAuthorization)
    private val deleteUseCase = DeleteBannerUseCase(bannerRepository, mediaStoragePort, platformAuthorization)
    private val reorderUseCase = ReorderBannersUseCase(bannerRepository, platformAuthorization)

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555040${(phoneCounter++).toString().padStart(4, '0')}")

    private val admin = User.registerWithPhone(nextPhone(), "Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val customer = User.registerWithPhone(nextPhone(), "Customer", UserRole.CUSTOMER).also { userRepository.save(it) }

    private fun uploadCommand(target: BannerTarget = BannerTarget.SITE) = UploadBannerCommand(
        callerId = admin.id,
        target = target,
        title = "Real Title",
        subtitle = null,
        href = "/salons",
        isActive = true,
        content = REAL_PNG_BYTES,
        mimeType = "image/png",
    )

    // ---------- upload: admin-only ----------

    @Test
    fun `an admin can upload a real banner for SITE`() {
        val banner = uploadUseCase.execute(uploadCommand(BannerTarget.SITE))
        assertEquals(BannerTarget.SITE, banner.target)
        assertTrue(mediaStoragePort.uploaded.containsKey(banner.storageKey))
    }

    @Test
    fun `a customer cannot upload a banner`() {
        assertThrows<PlatformAccessDeniedException> {
            uploadUseCase.execute(uploadCommand().copy(callerId = customer.id))
        }
    }

    @Test
    fun `uploading a disallowed mime type is rejected`() {
        assertThrows<MediaTypeInvalidException> {
            uploadUseCase.execute(uploadCommand().copy(mimeType = "application/pdf"))
        }
    }

    @Test
    fun `a spoofed content type (real bytes don't match the declared mime type) is rejected`() {
        assertThrows<MediaTypeInvalidException> {
            uploadUseCase.execute(uploadCommand().copy(content = byteArrayOf(1, 2, 3), mimeType = "image/png"))
        }
    }

    @Test
    fun `a second banner for the same target appends after the first, never reordering it`() {
        val first = uploadUseCase.execute(uploadCommand(BannerTarget.DESKTOP))
        val second = uploadUseCase.execute(uploadCommand(BannerTarget.DESKTOP))
        assertEquals(0, first.displayOrder)
        assertEquals(1, second.displayOrder)
    }

    // ---------- admin list vs public list ----------

    @Test
    fun `admin list includes inactive banners, public list never does`() {
        val active = uploadUseCase.execute(uploadCommand(BannerTarget.CUSTOMER))
        val inactive = uploadUseCase.execute(uploadCommand(BannerTarget.CUSTOMER)).also {
            updateMetadataUseCase.execute(UpdateBannerMetadataCommand(admin.id, it.id, it.title, it.subtitle, it.href, isActive = false))
        }

        val adminView = listUseCase.execute(ListBannersForAdminQuery(admin.id, BannerTarget.CUSTOMER))
        assertEquals(setOf(active.id, inactive.id), adminView.map { it.id }.toSet())

        val publicView = listActiveUseCase.execute(ListActiveBannersQuery(BannerTarget.CUSTOMER))
        assertEquals(listOf(active.id), publicView.map { it.id })
    }

    @Test
    fun `a customer cannot use the admin list endpoint`() {
        assertThrows<PlatformAccessDeniedException> {
            listUseCase.execute(ListBannersForAdminQuery(customer.id, BannerTarget.SITE))
        }
    }

    @Test
    fun `the public list requires no authorization at all - not even a real user id`() {
        uploadUseCase.execute(uploadCommand(BannerTarget.SITE))
        val result = listActiveUseCase.execute(ListActiveBannersQuery(BannerTarget.SITE))
        assertEquals(1, result.size)
    }

    // ---------- replace image: preserves id, swaps storage key, deletes the old file ----------

    @Test
    fun `replacing an image keeps the same banner id and deletes the old storage key`() {
        val original = uploadUseCase.execute(uploadCommand())
        val oldKey = original.storageKey

        val replaced = replaceImageUseCase.execute(
            ReplaceBannerImageCommand(admin.id, original.id, REAL_PNG_BYTES, "image/png"),
        )

        assertEquals(original.id, replaced.id)
        assertTrue(replaced.storageKey != oldKey)
        assertTrue(mediaStoragePort.deleted.contains(oldKey))
        assertTrue(mediaStoragePort.uploaded.containsKey(replaced.storageKey))
    }

    @Test
    fun `a customer cannot replace a banner image`() {
        val original = uploadUseCase.execute(uploadCommand())
        assertThrows<PlatformAccessDeniedException> {
            replaceImageUseCase.execute(ReplaceBannerImageCommand(customer.id, original.id, REAL_PNG_BYTES, "image/png"))
        }
    }

    // ---------- delete ----------

    @Test
    fun `an admin can delete a banner - row and storage object both go`() {
        val banner = uploadUseCase.execute(uploadCommand())
        deleteUseCase.execute(DeleteBannerCommand(admin.id, banner.id))

        assertEquals(null, bannerRepository.findById(banner.id))
        assertTrue(mediaStoragePort.deleted.contains(banner.storageKey))
    }

    @Test
    fun `a customer cannot delete a banner`() {
        val banner = uploadUseCase.execute(uploadCommand())
        assertThrows<PlatformAccessDeniedException> {
            deleteUseCase.execute(DeleteBannerCommand(customer.id, banner.id))
        }
    }

    @Test
    fun `deleting an unknown banner id throws`() {
        assertThrows<BannerNotFoundException> {
            deleteUseCase.execute(DeleteBannerCommand(admin.id, BannerId.new()))
        }
    }

    // ---------- reorder ----------

    @Test
    fun `an admin can reorder every banner within one target`() {
        val a = uploadUseCase.execute(uploadCommand(BannerTarget.DESKTOP))
        val b = uploadUseCase.execute(uploadCommand(BannerTarget.DESKTOP))

        reorderUseCase.execute(ReorderBannersCommand(admin.id, BannerTarget.DESKTOP, listOf(b.id, a.id)))

        assertEquals(0, bannerRepository.findById(b.id)!!.displayOrder)
        assertEquals(1, bannerRepository.findById(a.id)!!.displayOrder)
    }

    @Test
    fun `reordering with an id from a different target fails the whole call`() {
        val site = uploadUseCase.execute(uploadCommand(BannerTarget.SITE))
        val desktop = uploadUseCase.execute(uploadCommand(BannerTarget.DESKTOP))

        assertThrows<BannerReorderMismatchException> {
            reorderUseCase.execute(ReorderBannersCommand(admin.id, BannerTarget.DESKTOP, listOf(desktop.id, site.id)))
        }
    }
}
