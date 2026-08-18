package ai.rojan.backend.infrastructure.storage

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Registers [LocalMediaStorageProperties] - mirrors [MediaStorageConfig]'s own registration of [MediaStorageProperties], one config class per provider's property set. */
@Configuration
@EnableConfigurationProperties(LocalMediaStorageProperties::class)
class LocalMediaStorageConfig
