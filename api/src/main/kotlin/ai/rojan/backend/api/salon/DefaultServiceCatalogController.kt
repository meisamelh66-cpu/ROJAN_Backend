package ai.rojan.backend.api.salon

import ai.rojan.backend.domain.salon.DefaultServiceCatalog
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Default Beauty Salon Category Catalog - a fixed, salon-agnostic list of
 * category/service-type suggestions (see [DefaultServiceCatalog]). Not
 * nested under `/salons/{salonId}/...` like [ServiceCategoryController]:
 * this never reads or writes anything salon-specific, just the same static
 * list for every caller. Behind the default `anyRequest().authenticated()`
 * rule (`SecurityConfig`) like the rest of the Manager-facing API - no new
 * public route needed.
 */
@RestController
@RequestMapping("/api/v1/service-categories/default-catalog")
@Tag(name = "Service Categories")
class DefaultServiceCatalogController {

    @GetMapping
    @Operation(summary = "List the default beauty-salon category/service-type suggestions")
    fun list(): List<DefaultServiceCategoryResponse> =
        DefaultServiceCatalog.entries.map { DefaultServiceCategoryResponse(it.name, it.serviceTypes) }
}
