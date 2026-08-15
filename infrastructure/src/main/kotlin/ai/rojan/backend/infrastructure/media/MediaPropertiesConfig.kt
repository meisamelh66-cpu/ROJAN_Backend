package ai.rojan.backend.infrastructure.media

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(MediaProperties::class)
class MediaPropertiesConfig
