package ai.rojan.backend.bootstrap.config

import ai.rojan.backend.infrastructure.storage.LocalMediaStorageProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// PASS MEDIA-PUBLIC-01: lives in bootstrap, not infrastructure, because it needs
// org.springframework.web.servlet (spring-boot-starter-web), which only bootstrap and api
// depend on - infrastructure does not, and api does not depend on infrastructure at all (its own
// module graph only reaches domain/application), so bootstrap - the one module that already
// depends on every other one - is the only place this can correctly reference both
// WebMvcConfigurer and the real LocalMediaStorageProperties.storageRoot LocalDiskMediaStorageAdapter
// already writes to.
//
// Serves /media/** from that same real storage root - the actual gap this pass closes, not just
// an authorization rule. In real production, Nginx serves /media/** directly from the shared
// uploads volume, never reaching the JVM at all (LocalDiskMediaStorageAdapter's own doc comment,
// docker-compose.prod.yml's nginx service), so this handler never competes with it there; it only
// matters for a local/dev topology with no Nginx in front, where previously nothing served these
// files back out - the files existed on disk (LocalDiskMediaStorageAdapter.upload) but no route in
// the JVM itself could ever return their bytes.
//
// Registered broadly (/media/** -> the whole storage root, the same real correspondence
// LocalDiskMediaStorageAdapter.resolveWithinRoot already trusts) - SecurityConfig's own precise
// GET /media/salons/{salonId}/media/** pattern is the real gate deciding which of those files an
// anonymous request may actually reach; a DOCUMENT-typed asset (salons/{id}/documents/{uuid}) is
// physically servable by this handler but never authorized to reach it, so it stays exactly as
// protected as it already was.
@Configuration
class MediaResourceConfig(
    private val properties: LocalMediaStorageProperties,
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val storageRootLocation = "file:${properties.storageRoot.trimEnd('/')}/"
        registry.addResourceHandler("/media/**").addResourceLocations(storageRootLocation)
    }
}
