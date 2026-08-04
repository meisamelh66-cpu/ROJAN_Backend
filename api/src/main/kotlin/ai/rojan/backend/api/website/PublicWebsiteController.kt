package ai.rojan.backend.api.website

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/public")
class PublicWebsiteController {

    @GetMapping("/{tenantSlug}/website")
    fun getWebsite(
        @PathVariable tenantSlug: String
    ): Map<String, Any> {

        return mapOf(
            "tenant" to tenantSlug,
            "name" to "ROJAN AI",
            "description" to "AI Beauty Platform",
            "status" to "ACTIVE"
        )
    }
}