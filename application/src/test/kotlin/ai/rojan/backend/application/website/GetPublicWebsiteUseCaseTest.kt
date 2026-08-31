package ai.rojan.backend.application.website

import ai.rojan.backend.application.media.InMemoryMediaAssetRepository
import ai.rojan.backend.application.media.InMemoryMediaStoragePort
import ai.rojan.backend.application.salon.CreateSalonCommand
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class GetPublicWebsiteUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val mediaAssetRepository = InMemoryMediaAssetRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val mediaStoragePort = InMemoryMediaStoragePort()
    private val owner = UserId.new()

    private val useCase = GetPublicWebsiteUseCase(
        salonRepository, mediaAssetRepository, serviceRepository, specialistRepository, mediaStoragePort,
    )

    private fun createActiveSalon(slug: String = "salon-real-test"): Salon {
        val created = CreateSalonUseCase(salonRepository).execute(
            CreateSalonCommand(owner, "Real Test Salon", "A real salon description", "+1 555 0100", "hello@real.example", "1 Main St"),
        )
        created.changeSlug(slug)
        // CreateSalonUseCase itself always creates a real, un-onboarded DRAFT salon (the real
        // onboarding flow) - activate() is the same real transition ActivateSalonUseCase performs,
        // required here to reach "publicly discoverable".
        created.activate()
        return salonRepository.save(created)
    }

    @Test
    fun `returns the real salon's own data for an active, publicly discoverable slug`() {
        val salon = createActiveSalon()

        val result = useCase.execute(salon.slug)

        assertEquals(salon.id, result.salon.id)
        assertEquals("Real Test Salon", result.salon.name)
        assertEquals("A real salon description", result.salon.description)
    }

    @Test
    fun `throws SalonNotFoundException for an unknown slug`() {
        assertThrows<SalonNotFoundException> {
            useCase.execute("no-such-salon-slug")
        }
    }

    @Test
    fun `throws SalonNotFoundException for a DRAFT (not yet activated) salon - never distinguishable from unknown`() {
        // CreateSalonUseCase itself always creates a real, un-onboarded DRAFT salon (the real
        // onboarding flow) - deliberately never calling activate() here exercises that real state.
        val draft = CreateSalonUseCase(salonRepository).execute(
            CreateSalonCommand(owner, "Draft Salon", null, "+1 555 0200", null, "2 Draft St"),
        )
        draft.changeSlug("salon-draft-test")
        salonRepository.save(draft)
        assertEquals(SalonOnboardingStatus.DRAFT, draft.onboardingStatus)

        assertThrows<SalonNotFoundException> {
            useCase.execute("salon-draft-test")
        }
    }

    @Test
    fun `throws SalonNotFoundException for a deactivated salon`() {
        val salon = createActiveSalon("salon-inactive-test")
        salon.deactivate()
        salonRepository.save(salon)

        assertThrows<SalonNotFoundException> {
            useCase.execute("salon-inactive-test")
        }
    }

    @Test
    fun `resolves real logo and cover media URLs when identity media is assigned`() {
        val salon = createActiveSalon("salon-media-test")
        val logo = mediaAssetRepository.save(
            MediaAsset.create(salon.id, MediaType.LOGO, "salons/${salon.id.value}/media/logo.png", "logo.png", "image/png", 100, owner),
        )
        val cover = mediaAssetRepository.save(
            MediaAsset.create(salon.id, MediaType.COVER, "salons/${salon.id.value}/media/cover.png", "cover.png", "image/png", 100, owner),
        )
        salon.assignIdentityMedia(IdentitySlot.LOGO, logo.id)
        salon.assignIdentityMedia(IdentitySlot.COVER, cover.id)
        salonRepository.save(salon)

        val result = useCase.execute("salon-media-test")

        assertEquals("https://cdn.test/${logo.storageKey}", result.logoUrl)
        assertEquals("https://cdn.test/${cover.storageKey}", result.coverUrl)
    }

    @Test
    fun `real gallery media stays compatible - active GALLERY assets are included, targeted PORTFOLIO and inactive assets are not`() {
        val salon = createActiveSalon("salon-gallery-test")
        val galleryAsset = mediaAssetRepository.save(
            MediaAsset.create(salon.id, MediaType.GALLERY, "salons/${salon.id.value}/media/g1.png", "g1.png", "image/png", 100, owner),
        )
        val targetedPortfolio = mediaAssetRepository.save(
            MediaAsset.create(
                salon.id, MediaType.PORTFOLIO, "salons/${salon.id.value}/media/p1.png", "p1.png", "image/png", 100, owner,
                targetId = java.util.UUID.randomUUID(),
            ),
        )
        val archived = mediaAssetRepository.save(
            MediaAsset.create(salon.id, MediaType.GALLERY, "salons/${salon.id.value}/media/g2.png", "g2.png", "image/png", 100, owner),
        )
        archived.archive()
        mediaAssetRepository.save(archived)

        val result = useCase.execute("salon-gallery-test")

        assertTrue(result.galleryUrls.contains("https://cdn.test/${galleryAsset.storageKey}"))
        assertFalse(result.galleryUrls.contains("https://cdn.test/${targetedPortfolio.storageKey}"))
        assertFalse(result.galleryUrls.contains("https://cdn.test/${archived.storageKey}"))
    }

    @Test
    fun `only active services and specialists appear in the content summaries`() {
        val salon = createActiveSalon("salon-summary-test")
        val activeService = Service.create(salon.id, ServiceCategoryId.new(), "Haircut", null, 30, BigDecimal.TEN)
        val inactiveService = Service.create(salon.id, ServiceCategoryId.new(), "Old Service", null, 30, BigDecimal.TEN)
        inactiveService.deactivate()
        serviceRepository.save(activeService)
        serviceRepository.save(inactiveService)

        val activeSpecialist = Specialist.create(salon.id, null, "Active Stylist", null, null)
        val inactiveSpecialist = Specialist.create(salon.id, null, "Former Stylist", null, null)
        inactiveSpecialist.deactivate()
        specialistRepository.save(activeSpecialist)
        specialistRepository.save(inactiveSpecialist)

        val result = useCase.execute("salon-summary-test")

        assertEquals(listOf("Haircut"), result.services.map { it.name })
        assertEquals(listOf("Active Stylist"), result.specialists.map { it.name })
    }
}
